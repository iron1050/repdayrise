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
    public FocusPreferences Focus { get; init; } = new();
    public SkyQuality SkyQuality { get; init; } = SkyQuality.High;
}

/// <summary>Eco keeps the animated sky to a low frame rate.</summary>
public enum SkyQuality { High, Eco }

/// <summary>Focus session preferences. Older settings files without them load these defaults.</summary>
public sealed record FocusPreferences
{
    public int FocusMinutes { get; init; } = 25;
    public int CustomMinutes { get; init; } = 40;
    public int ShortBreakMinutes { get; init; } = 5;
    public int LongBreakMinutes { get; init; } = 15;
    public int LongBreakEvery { get; init; } = 4;
    public bool BreaksEnabled { get; init; } = true;
    public bool AutoStartBreaks { get; init; } = true;
    public bool AutoStartFocus { get; init; }
    public bool CountPartial { get; init; } = true;
    /// <summary>Hold habit reminders while a focus phase is running.</summary>
    public bool QuietReminders { get; init; } = true;
    public bool Sound { get; init; } = true;
    /// <summary>The habit the last session was tagged with, by uid so it survives a sync.</summary>
    public string? LastHabitUid { get; init; }
    /// <summary>Focused minutes a day needs to keep the focus streak going.</summary>
    public int DailyGoalMinutes { get; init; } = 25;

    public FocusConfig ToConfig(int? focusMinutes = null) => new()
    {
        FocusMs = Math.Clamp(focusMinutes ?? FocusMinutes, 1, 600) * 60_000L,
        ShortBreakMs = Math.Clamp(ShortBreakMinutes, 1, 120) * 60_000L,
        LongBreakMs = Math.Clamp(LongBreakMinutes, 1, 120) * 60_000L,
        LongBreakEvery = Math.Max(LongBreakEvery, 1),
        BreaksEnabled = BreaksEnabled,
        AutoStartBreaks = AutoStartBreaks,
        AutoStartFocus = AutoStartFocus,
        CountPartial = CountPartial,
    };
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
