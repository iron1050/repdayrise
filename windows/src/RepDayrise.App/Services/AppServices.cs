using System.Reflection;
using Microsoft.UI.Dispatching;
using RepDayrise.Core.Data;
using RepDayrise.Core.Domain;
using RepDayrise.Core.Sharing;
using Windows.Storage;

namespace RepDayrise.Services;

/// <summary>The app's single set of services, created once at startup.</summary>
public static class AppServices
{
    private static DispatcherQueue? _ui;
    private static DispatcherQueueTimer? _coalesce;

    public static HabitStore Store { get; private set; } = null!;
    public static SettingsStore Settings { get; private set; } = null!;
    public static SharingService Sharing { get; private set; } = null!;
    public static ReminderScheduler Reminders { get; private set; } = null!;

    /// <summary>Data folder inside the package. Windows removes it when the app is uninstalled.</summary>
    public static string DataFolder { get; private set; } = "";

    /// <summary>Raised on the UI thread, at most once per frame burst, after habits, entries or timers change.</summary>
    public static event Action? DataChanged;

    /// <summary>Raised on the UI thread after the sharing state changes.</summary>
    public static event Action? SharingChanged;

    /// <summary>Raised on the UI thread after settings change.</summary>
    public static event Action<Settings>? SettingsChanged;

    public static HabitLogic Logic => new(Settings.Current.WeekStartsMonday ? DayOfWeek.Monday : DayOfWeek.Sunday);

    public static void Initialize(DispatcherQueue ui)
    {
        _ui = ui;
        DataFolder = ApplicationData.Current.LocalFolder.Path;
        Store = new HabitStore(Path.Combine(DataFolder, "dayrise.db"));
        Settings = new SettingsStore(Path.Combine(DataFolder, "settings.json"));
        Sharing = new SharingService(Path.Combine(DataFolder, "sharing.json"), new StoreSnapshotSource(Store, Settings), DefaultSharingUrl());
        Reminders = new ReminderScheduler();

        _coalesce = ui.CreateTimer();
        _coalesce.Interval = TimeSpan.FromMilliseconds(50);
        _coalesce.IsRepeating = false;
        _coalesce.Tick += (_, _) => DataChanged?.Invoke();

        Store.Changed += change =>
        {
            OnUi(() => _coalesce.Start());
            if (change.HasFlag(DataChange.Habits) || change.HasFlag(DataChange.Entries))
            {
                Sharing.RequestSync();
                Reminders.RequestReschedule();
            }
        };
        Sharing.Changed += () => OnUi(() => SharingChanged?.Invoke());
        Settings.Changed += s =>
        {
            Sharing.RequestSync(); // the week start travels with the snapshot
            OnUi(() => SettingsChanged?.Invoke(s));
        };
    }

    public static void OnUi(Action action)
    {
        if (_ui == null || _ui.HasThreadAccess) action();
        else _ui.TryEnqueue(() => action());
    }

    private static string DefaultSharingUrl() =>
        typeof(AppServices).Assembly.GetCustomAttributes<AssemblyMetadataAttribute>()
            .FirstOrDefault(a => a.Key == "DayriseSharingUrl")?.Value ?? "";
}
