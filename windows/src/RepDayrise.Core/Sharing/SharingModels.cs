using System.Text.Json;
using System.Text.Json.Serialization;
using RepDayrise.Core.Models;

namespace RepDayrise.Core.Sharing;

public static class SharingJson
{
    /// <summary>camelCase like kotlinx.serialization on Android; unknown fields from newer apps are ignored by default.</summary>
    public static readonly JsonSerializerOptions Options = new(JsonSerializerDefaults.Web)
    {
        DefaultIgnoreCondition = JsonIgnoreCondition.Never,
    };

    public static string Encode<T>(T value) => JsonSerializer.Serialize(value, Options);
    public static T Decode<T>(string json) => JsonSerializer.Deserialize<T>(json, Options)!;
}

/// <summary>A habit as a partner sees it. Notes, reminders and groups stay on the owner's device.</summary>
public sealed record SharedHabit
{
    /// <summary>How many days of history a shared snapshot carries: enough for the year graph and its streaks.</summary>
    public const int SnapshotDays = 400;

    public long Id { get; init; }
    public string Name { get; init; } = "";
    public string Icon { get; init; } = "sun";
    public int ColorIndex { get; init; }
    public string Type { get; init; } = nameof(HabitType.CHECK);
    public double Goal { get; init; } = 1.0;
    public string Unit { get; init; } = "";
    public string ScheduleType { get; init; } = nameof(Models.ScheduleType.DAILY);
    public int DaysMask { get; init; } = Models.DaysMask.ALL;
    public int TimesPerPeriod { get; init; } = 1;
    public long StartDate { get; init; }

    public Habit ToHabit() => new()
    {
        Id = Id, Name = Name, Icon = Icon, ColorIndex = ColorIndex,
        Type = Enum.TryParse<HabitType>(Type, out var t) && Enum.IsDefined(t) ? t : HabitType.CHECK,
        Goal = Goal, Unit = Unit,
        ScheduleType = Enum.TryParse<ScheduleType>(ScheduleType, out var s) && Enum.IsDefined(s) ? s : Models.ScheduleType.DAILY,
        DaysMask = DaysMask, TimesPerPeriod = Math.Max(TimesPerPeriod, 1),
        StartDate = EpochDay.ToDate(StartDate),
    };

    public static SharedHabit From(Habit h) => new()
    {
        Id = h.Id, Name = h.Name, Icon = h.Icon, ColorIndex = h.ColorIndex, Type = h.Type.ToString(), Goal = h.Goal, Unit = h.Unit,
        ScheduleType = h.ScheduleType.ToString(), DaysMask = h.DaysMask, TimesPerPeriod = h.TimesPerPeriod, StartDate = h.StartDate.ToEpochDay(),
    };
}

/// <summary>Everything a partner needs to draw the owner's list: habits plus habitId -> (epochDay -> value).</summary>
public sealed record Snapshot
{
    public int Schema { get; init; } = 1;
    public bool WeekStartsMonday { get; init; } = true;
    public List<SharedHabit> Habits { get; init; } = [];
    public EntryMap Entries { get; init; } = new();

    /// <summary>Builds the snapshot to publish: active, non-excluded habits and their recent entries.</summary>
    public static Snapshot Build(IEnumerable<Habit> habits, EntryMap entries, IReadOnlySet<long> excluded, bool weekStartsMonday, DateOnly today)
    {
        var shared = habits.Where(h => !h.Archived && !excluded.Contains(h.Id)).ToList();
        var cutoff = today.AddDays(-SharedHabit.SnapshotDays).ToEpochDay();
        var last = today.ToEpochDay();
        var map = new EntryMap();
        foreach (var h in shared)
        {
            if (!entries.TryGetValue(h.Id, out var days)) continue;
            var kept = days.Where(kv => kv.Key >= cutoff && kv.Key <= last && kv.Value > 0.0)
                .OrderBy(kv => kv.Key)
                .ToDictionary(kv => kv.Key, kv => kv.Value);
            if (kept.Count > 0) map[h.Id] = kept;
        }
        return new Snapshot { WeekStartsMonday = weekStartsMonday, Habits = shared.Select(SharedHabit.From).ToList(), Entries = map };
    }
}

/// <summary>Someone following the owner's list.</summary>
public sealed record Partner
{
    public string Id { get; init; } = "";
    public string Name { get; init; } = "";
    public long JoinedAt { get; init; }
    public long? LastSeenAt { get; init; }
}

/// <summary>The list this device publishes.</summary>
public sealed record Outgoing
{
    public required string ServerUrl { get; init; }
    public required string ShareId { get; init; }
    public required string Token { get; init; }
    public required string Code { get; init; }
    public HashSet<long> ExcludedHabitIds { get; init; } = [];
    public List<Partner> Partners { get; init; } = [];
    public long LastSyncedAt { get; init; }
    public string LastPushedDigest { get; init; } = "";
    public string? LastError { get; init; }
}

/// <summary>A list this device subscribes to, with the last snapshot it fetched.</summary>
public sealed record Following
{
    public required string ServerUrl { get; init; }
    public required string ShareId { get; init; }
    public required string Token { get; init; }
    public required string OwnerName { get; init; }
    public int Version { get; init; }
    public long UpdatedAt { get; init; }
    public long FetchedAt { get; init; }
    public Snapshot? Snapshot { get; init; }
    /// <summary>The owner removed us or stopped sharing; the cached snapshot is all that's left.</summary>
    public bool Ended { get; init; }
}

public sealed record SharingState
{
    /// <summary>Overrides the default address. Blank means use the default.</summary>
    public string ServerUrl { get; init; } = "";
    public string DisplayName { get; init; } = "";
    public Outgoing? Outgoing { get; init; }
    public List<Following> Following { get; init; } = [];
}

// Wire formats

internal sealed record NameBody(string Name);
internal sealed record CreateShareResponse(string Id, string Token, string Code);
internal sealed record PublishBody(string Name, Snapshot Snapshot);
internal sealed record StatusResponse(string Code, List<Partner>? Subscribers);
internal sealed record CodeResponse(string Code);
internal sealed record JoinBody(string Code, string Name);
internal sealed record JoinResponse(string Token, string ShareId, string OwnerName);
internal sealed record FeedResponse(string OwnerName, int Version, long UpdatedAt, Snapshot? Snapshot);
internal sealed record ErrorBody(string? Error, string? Message);
