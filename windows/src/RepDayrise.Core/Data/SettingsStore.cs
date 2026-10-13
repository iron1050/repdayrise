using RepDayrise.Core.Models;
using RepDayrise.Core.Sharing;

namespace RepDayrise.Core.Data;

/// <summary>Preferences for this device. Kept out of the habit database so a future sync never carries them.</summary>
public sealed record Settings
{
    public ThemeMode ThemeMode { get; init; } = ThemeMode.SYSTEM;
    public bool WeekStartsMonday { get; init; } = true;
    public bool OnboardingDone { get; init; }
    public bool CompletedToBottom { get; init; } = true;
}

public sealed class SettingsStore(string path)
{
    private readonly Lock _lock = new();
    private Settings? _current;

    public event Action<Settings>? Changed;

    public Settings Current
    {
        get
        {
            lock (_lock)
            {
                if (_current != null) return _current;
                try { _current = SharingJson.Decode<Settings>(File.ReadAllText(path)); }
                catch { _current = new Settings(); }
                return _current;
            }
        }
    }

    public void Update(Func<Settings, Settings> transform)
    {
        Settings next;
        lock (_lock)
        {
            next = transform(Current);
            _current = next;
            Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(path))!);
            var tmp = path + ".tmp";
            File.WriteAllText(tmp, SharingJson.Encode(next));
            File.Move(tmp, path, overwrite: true);
        }
        Changed?.Invoke(next);
    }
}

/// <summary>Feeds the sharing service from the local store and settings.</summary>
public sealed class StoreSnapshotSource(HabitStore store, SettingsStore settings) : ISnapshotSource
{
    public Task<IReadOnlyList<Habit>> GetAllHabitsAsync() => Task.Run(store.GetAllHabits);
    public Task<EntryMap> GetEntriesAsync() => Task.Run(store.GetEntries);
    public bool WeekStartsMonday => settings.Current.WeekStartsMonday;
}
