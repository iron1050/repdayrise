using Windows.Foundation.Metadata;
using Windows.UI.Shell;

namespace RepDayrise.Services;

/// <summary>
/// Do Not Disturb while focusing. Apps can't switch Windows Focus on themselves (that API is limited to partners
/// Microsoft approves), so we do the two things we can: hold our own habit reminders while a focus phase runs, and
/// read whether Windows Focus is on so the Focus page can show it, with a shortcut to turn it on.
/// </summary>
public static class QuietHours
{
    private static FocusSessionManager? _manager;

    /// <summary>Raised (on any thread) when Windows Focus turns on or off.</summary>
    public static event Action? Changed;

    public static void Initialize()
    {
        try
        {
            if (!ApiInformation.IsTypePresent("Windows.UI.Shell.FocusSessionManager") || !FocusSessionManager.IsSupported) return;
            _manager = FocusSessionManager.GetDefault();
            _manager.IsFocusActiveChanged += (_, _) => Changed?.Invoke();
        }
        catch (Exception e)
        {
            System.Diagnostics.Debug.WriteLine($"Windows Focus state unavailable: {e}");
            _manager = null;
        }
    }

    /// <summary>Whether we can read the Windows Focus state on this PC.</summary>
    public static bool CanReadSystemFocus => _manager != null;

    /// <summary>Whether Windows Focus (which silences notifications) is on right now.</summary>
    public static bool SystemFocusActive
    {
        get
        {
            try { return _manager?.IsFocusActive ?? false; }
            catch { return false; }
        }
    }

    /// <summary>Opens the Windows page where Focus and Do Not Disturb are switched on.</summary>
    public static async Task OpenSystemSettings()
    {
        if (!await Windows.System.Launcher.LaunchUriAsync(new Uri("ms-settings:focus")))
            await Windows.System.Launcher.LaunchUriAsync(new Uri("ms-settings:notifications"));
    }

    /// <summary>
    /// The time (Unix ms) until which reminders should be held, or null. Reminders that fall inside a running focus
    /// phase move to just after it ends, so they're delayed, not lost.
    /// </summary>
    public static long? HoldRemindersUntil()
    {
        if (!AppServices.Settings.Current.Focus.QuietReminders) return null;
        var run = AppServices.Store.GetFocusRun();
        return run is { Running: true, IsBreak: false, PhaseEndsAt: long end } ? end : null;
    }
}
