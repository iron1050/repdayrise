using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using RepDayrise.Core.Models;

namespace RepDayrise.Core.Sharing;

/// <summary>What the sharing service needs to read from the habit store.</summary>
public interface ISnapshotSource
{
    Task<IReadOnlyList<Habit>> GetAllHabitsAsync();
    Task<EntryMap> GetEntriesAsync();
    bool WeekStartsMonday { get; }
}

/// <summary>
/// Publishes this device's habit list to accountability partners and follows other people's.
/// A port of the Android app's SharingRepository.kt.
///
/// State lives in its own JSON file rather than the habit database: it holds bearer tokens, and copying the
/// database to a second device shouldn't silently share one identity.
/// </summary>
public sealed partial class SharingService
{
    private readonly SharingApi _api;
    private readonly ISnapshotSource _source;
    private readonly string _file;
    private readonly string _defaultServerUrl;
    private readonly SemaphoreSlim _writeLock = new(1, 1);
    private readonly SemaphoreSlim _pushLock = new(1, 1);
    private readonly Lock _stateLock = new();
    private CancellationTokenSource? _pendingSync;
    private SharingState _state;

    /// <summary>Raised after any state change, on whatever thread made it.</summary>
    public event Action? Changed;

    public SharingService(string stateFile, ISnapshotSource source, string defaultServerUrl = "", HttpClient? http = null)
    {
        _file = stateFile;
        _source = source;
        _defaultServerUrl = NormalizeUrl(defaultServerUrl);
        _api = new SharingApi(http);
        _state = Load();
        RequestSync();
    }

    public SharingState State { get { lock (_stateLock) return _state; } }

    /// <summary>The address new shares and joins go to: the in-app override, else the default.</summary>
    public string DefaultServerUrl => _defaultServerUrl;
    private string ServerUrl() { var s = NormalizeUrl(State.ServerUrl); return s.Length > 0 ? s : _defaultServerUrl; }

    private SharingState Load()
    {
        try { return SharingJson.Decode<SharingState>(File.ReadAllText(_file)); }
        catch { return new SharingState(); }
    }

    private async Task Edit(Func<SharingState, SharingState> transform)
    {
        SharingState snapshot;
        lock (_stateLock) { _state = transform(_state); snapshot = _state; }
        await _writeLock.WaitAsync().ConfigureAwait(false);
        try
        {
            Directory.CreateDirectory(Path.GetDirectoryName(_file)!);
            var tmp = _file + ".tmp";
            await File.WriteAllTextAsync(tmp, SharingJson.Encode(snapshot)).ConfigureAwait(false);
            File.Move(tmp, _file, overwrite: true);
        }
        finally { _writeLock.Release(); }
        Changed?.Invoke();
    }

    private Task EditOutgoing(Func<Outgoing, Outgoing> transform) =>
        Edit(s => s with { Outgoing = s.Outgoing is { } o ? transform(o) : null });

    private Task EditFollowing(string shareId, Func<Following, Following> transform) =>
        Edit(s => s with { Following = s.Following.Select(f => f.ShareId == shareId ? transform(f) : f).ToList() });

    /// <summary>Runs a network action. Returns null on success, or a sentence a person can act on.</summary>
    private static async Task<string?> Attempt(Func<Task> block)
    {
        try { await block().ConfigureAwait(false); return null; }
        catch (OperationCanceledException) { throw; }
        catch (Exception e) { return Describe(e); }
    }

    private static string Describe(Exception e) => e switch
    {
        ApiException => e.Message,
        HttpRequestException or TaskCanceledException or IOException => "Couldn't reach the server. Check your connection and try again.",
        _ => string.IsNullOrEmpty(e.Message) ? "Something went wrong." : e.Message,
    };

    private string RequireServer()
    {
        var s = ServerUrl();
        return s.Length > 0 ? s : throw new InvalidOperationException("Add a server address first.");
    }

    public Task SetServerUrl(string url) => Edit(s => s with { ServerUrl = NormalizeUrl(url) });

    // ---- Publishing your own list ---------------------------------------------------------

    public Task<string?> StartSharing(string name) => Attempt(async () =>
    {
        var baseUrl = RequireServer();
        var clean = name.Trim();
        var created = await _api.CreateShare(baseUrl, clean);
        await Edit(s => s with { DisplayName = clean, Outgoing = new Outgoing { ServerUrl = baseUrl, ShareId = created.Id, Token = created.Token, Code = created.Code } });
        await Push();
    });

    /// <summary>Ask for the list to be re-published in two seconds. Safe to call on every data change.</summary>
    public void RequestSync()
    {
        if (State.Outgoing == null) return;
        var cts = new CancellationTokenSource();
        Interlocked.Exchange(ref _pendingSync, cts)?.Cancel();
        _ = Task.Delay(2000, cts.Token).ContinueWith(async t =>
        {
            if (!t.IsCanceled) await Push();
        }, TaskScheduler.Default);
    }

    /// <summary>Publishes the current list if it differs from what the server already has.</summary>
    public async Task Push(bool force = false)
    {
        await _pushLock.WaitAsync().ConfigureAwait(false);
        try
        {
            var outgoing = State.Outgoing;
            if (outgoing == null) return;
            try
            {
                var snapshot = Snapshot.Build(await _source.GetAllHabitsAsync(), await _source.GetEntriesAsync(), outgoing.ExcludedHabitIds,
                    _source.WeekStartsMonday, EpochDay.Today());
                var name = State.DisplayName;
                var digest = Convert.ToHexString(SHA256.HashData(Encoding.UTF8.GetBytes(name + SharingJson.Encode(snapshot))));
                if (force || digest != outgoing.LastPushedDigest || outgoing.LastError != null)
                    await _api.Publish(outgoing.ServerUrl, outgoing.ShareId, outgoing.Token, name, snapshot);
                await EditOutgoing(o => o with { LastSyncedAt = Clock.NowMs(), LastPushedDigest = digest, LastError = null });
            }
            catch (OperationCanceledException) { throw; }
            catch (Exception e)
            {
                var gone = e is ApiException { Status: 401 };
                await EditOutgoing(o => o with { LastError = gone ? "This list no longer exists on the server. Stop sharing and start again." : Describe(e) });
            }
        }
        finally { _pushLock.Release(); }
    }

    /// <summary>Refreshes the invite code and the list of partners from the server.</summary>
    public Task<string?> RefreshOutgoing() => Attempt(async () =>
    {
        if (State.Outgoing is not { } o) return;
        var status = await _api.Status(o.ServerUrl, o.ShareId, o.Token);
        await EditOutgoing(x => x with { Code = status.Code, Partners = status.Subscribers ?? [] });
    });

    public async Task SetHabitShared(long habitId, bool shared)
    {
        await EditOutgoing(o =>
        {
            var set = new HashSet<long>(o.ExcludedHabitIds);
            if (shared) set.Remove(habitId); else set.Add(habitId);
            return o with { ExcludedHabitIds = set };
        });
        RequestSync();
    }

    public Task<string?> RotateCode() => Attempt(async () =>
    {
        if (State.Outgoing is not { } o) return;
        var code = await _api.RotateCode(o.ServerUrl, o.ShareId, o.Token);
        await EditOutgoing(x => x with { Code = code });
    });

    public Task<string?> RemovePartner(string partnerId) => Attempt(async () =>
    {
        if (State.Outgoing is not { } o) return;
        await _api.RemovePartner(o.ServerUrl, o.ShareId, o.Token, partnerId);
        await EditOutgoing(x => x with { Partners = x.Partners.Where(p => p.Id != partnerId).ToList() });
    });

    /// <summary>Deletes the list from the server, which also ends every partner's subscription.</summary>
    public Task<string?> StopSharing() => Attempt(async () =>
    {
        if (State.Outgoing is not { } o) return;
        try { await _api.DeleteShare(o.ServerUrl, o.ShareId, o.Token); }
        catch (ApiException e) when (e.Status == 401) { /* already gone, which is what we wanted */ }
        await Edit(s => s with { Outgoing = null });
    });

    // ---- Following someone else's list ----------------------------------------------------

    public Task<string?> Follow(string code, string name) => Attempt(async () =>
    {
        var baseUrl = RequireServer();
        var clean = name.Trim();
        var joined = await _api.Join(baseUrl, code.Trim(), clean);
        if (joined.ShareId == State.Outgoing?.ShareId)
        {
            try { await _api.Unsubscribe(baseUrl, joined.Token); } catch { /* best effort */ }
            throw new InvalidOperationException("That's your own code. Send it to a partner instead.");
        }
        var previous = State.Following.FirstOrDefault(f => f.ShareId == joined.ShareId);
        if (previous is { Ended: false })
        {
            try { await _api.Unsubscribe(previous.ServerUrl, previous.Token); } catch { /* best effort */ }
        }
        var entry = new Following { ServerUrl = baseUrl, ShareId = joined.ShareId, Token = joined.Token, OwnerName = joined.OwnerName };
        await Edit(s => s with
        {
            DisplayName = string.IsNullOrWhiteSpace(s.DisplayName) ? clean : s.DisplayName,
            Following = s.Following.Where(f => f.ShareId != joined.ShareId).Append(entry).ToList(),
        });
        await Refresh(entry);
    });

    public async Task Unfollow(string shareId)
    {
        var f = State.Following.FirstOrDefault(x => x.ShareId == shareId);
        if (f == null) return;
        if (!f.Ended)
        {
            try { await _api.Unsubscribe(f.ServerUrl, f.Token); } catch { /* best effort */ }
        }
        await Edit(s => s with { Following = s.Following.Where(x => x.ShareId != shareId).ToList() });
    }

    /// <summary>Fetches the latest snapshot for every list this device follows. Reports the first failure.</summary>
    public async Task<string?> RefreshFollowing()
    {
        string? failure = null;
        foreach (var f in State.Following)
        {
            if (f.Ended) continue;
            var result = await Attempt(() => Refresh(f));
            failure ??= result;
        }
        return failure;
    }

    private async Task Refresh(Following f)
    {
        try
        {
            var feed = await _api.Feed(f.ServerUrl, f.ShareId, f.Token, f.Snapshot == null ? -1 : f.Version);
            var now = Clock.NowMs();
            await EditFollowing(f.ShareId, x => feed == null
                ? x with { FetchedAt = now }
                : x with { OwnerName = feed.OwnerName, Version = feed.Version, UpdatedAt = feed.UpdatedAt, FetchedAt = now, Snapshot = feed.Snapshot ?? x.Snapshot });
        }
        catch (ApiException e) when (e.Status == 401)
        {
            await EditFollowing(f.ShareId, x => x with { Ended = true });
        }
    }

    /// <summary>Tidies a typed address: trims, drops trailing slashes, assumes https when no scheme is given.</summary>
    public static string NormalizeUrl(string raw)
    {
        var t = raw.Trim().TrimEnd('/');
        if (t.Length == 0) return "";
        return t.StartsWith("http://", StringComparison.Ordinal) || t.StartsWith("https://", StringComparison.Ordinal) ? t : "https://" + t;
    }

    /// <summary>Pulls the invite code out of whatever was pasted: a bare code, a dayrise:// link or an https invite link.</summary>
    public static string ExtractCode(string raw)
    {
        var t = raw.Trim();
        var m = InviteLinkPattern().Match(t);
        var tail = m.Success ? m.Groups[1].Value : t;
        return new string(tail.ToUpperInvariant().Where(char.IsLetterOrDigit).ToArray());
    }

    public static string InviteLink(string serverUrl, string code) => $"{serverUrl}/j/{new string(code.Where(char.IsLetterOrDigit).ToArray())}";

    [GeneratedRegex("(?:/j/|://join/)([A-Za-z0-9-]+)")]
    private static partial Regex InviteLinkPattern();
}
