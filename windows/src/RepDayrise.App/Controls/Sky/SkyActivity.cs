using Windows.System.Power;
using Windows.UI.ViewManagement;

namespace RepDayrise.Controls.Sky;

/// <summary>How hard the animated sky may work right now.</summary>
public enum SkyMode
{
    /// <summary>Animate at the normal frame rate.</summary>
    Full,
    /// <summary>Animate slowly: the window is in the background, or the user picked Eco.</summary>
    Low,
    /// <summary>Draw one frame per change: animations are off or battery saver is on.</summary>
    Static,
    /// <summary>Draw nothing: the window is minimised or hidden.</summary>
    Hidden,
}

/// <summary>
/// One app-wide gate for the GPU sky. The main window reports visibility and focus; this adds battery saver,
/// the Windows "animation effects" setting and the user's quality choice, and tells every sky what it may do.
/// </summary>
public static class SkyActivity
{
    private static readonly UISettings Ui = new();
    private static bool _visible = true;
    private static bool _active = true;
    private static bool _eco;
    private static bool _forceCpu = Environment.GetEnvironmentVariable("DAYRISE_SKY") == "cpu";
    private static DateTimeOffset _inactiveSince = DateTimeOffset.MaxValue;
    private static Microsoft.UI.Dispatching.DispatcherQueueTimer? _idleTimer;

    public static event Action? Changed;

    public static SkyMode Mode { get; private set; } = SkyMode.Full;

    /// <summary>True when the shader must not be used (DAYRISE_SKY=cpu, or the GPU path failed once).</summary>
    public static bool ForceCpu => _forceCpu;

    /// <summary>Frames per second for <see cref="SkyMode.Full"/> and <see cref="SkyMode.Low"/>.</summary>
    public static double FramesPerSecond => Mode == SkyMode.Full ? 30 : _active ? 15 : 5;

    public static void Initialize(Microsoft.UI.Dispatching.DispatcherQueue dispatcher)
    {
        PowerManager.EnergySaverStatusChanged += (_, _) => dispatcher.TryEnqueue(Recompute);
        Ui.AnimationsEnabledChanged += (_, _) => dispatcher.TryEnqueue(Recompute);
        _idleTimer = dispatcher.CreateTimer();
        _idleTimer.Interval = TimeSpan.FromSeconds(31);
        _idleTimer.IsRepeating = false;
        _idleTimer.Tick += (_, _) => Recompute();
        Recompute();
    }

    public static void SetVisible(bool visible) { _visible = visible; Recompute(); }

    public static void SetActive(bool active)
    {
        _active = active;
        _inactiveSince = active ? DateTimeOffset.MaxValue : DateTimeOffset.Now;
        if (!active) _idleTimer?.Start();
        Recompute();
    }

    public static void SetEco(bool eco) { _eco = eco; Recompute(); }

    /// <summary>Called by a sky whose shader failed; every sky falls back to the CPU drawing from then on.</summary>
    public static void ReportGpuFailure()
    {
        if (_forceCpu) return;
        _forceCpu = true;
        Changed?.Invoke();
    }

    private static void Recompute()
    {
        bool saver;
        try { saver = PowerManager.EnergySaverStatus == EnergySaverStatus.On; } catch { saver = false; }
        var idle = !_active && DateTimeOffset.Now - _inactiveSince > TimeSpan.FromSeconds(30);
        var mode =
            !_visible ? SkyMode.Hidden :
            saver || !Ui.AnimationsEnabled ? SkyMode.Static :
            _eco || idle ? SkyMode.Low :
            SkyMode.Full;
        Mode = mode;
        Changed?.Invoke();
    }
}
