using System.Runtime.InteropServices;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media.Animation;
using Microsoft.UI.Xaml.Navigation;
using RepDayrise.Controls.Sky;
using RepDayrise.Core.Data;
using RepDayrise.Core.Models;
using RepDayrise.Services;
using RepDayrise.Views;
using Windows.System;
using Windows.UI;
using WinRT.Interop;

namespace RepDayrise;

/// <summary>The three top-level destinations in the title bar.</summary>
public enum Destination { Focus, Progress, Habits }

/// <summary>Pages drawn over the dark sky: the title bar switches to light-on-dark over them.</summary>
public interface ISkyPage;

public sealed partial class MainWindow : Window
{
    private static readonly Dictionary<Destination, Type> Destinations = new()
    {
        [Destination.Focus] = typeof(FocusPage),
        [Destination.Progress] = typeof(ProgressPage),
        [Destination.Habits] = typeof(HabitsPage),
    };

    private Destination? _current;
    private bool _syncingTabs;
    private readonly Microsoft.UI.Dispatching.DispatcherQueueTimer _heartbeat;
    private readonly TaskbarProgress _taskbar;
    private Windows.Media.Playback.MediaPlayer? _chime;

    /// <summary>Raised on the UI thread about twice a second with the focus run (after completing any due phase).</summary>
    public static event Action<FocusRun?>? FocusHeartbeat;

    public MainWindow()
    {
        InitializeComponent();
        ExtendsContentIntoTitleBar = true;
        AppWindow.TitleBar.PreferredHeightOption = TitleBarHeightOption.Tall;
        SetTitleBar(AppTitleBar);
        AppWindow.SetIcon("Assets/AppIcon.ico");
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1180, 820));
        if (AppWindow.Presenter is OverlappedPresenter presenter)
        {
            presenter.PreferredMinimumWidth = 560;
            presenter.PreferredMinimumHeight = 600;
        }

        // The GPU sky only animates while it can be seen.
        SkyActivity.Initialize(DispatcherQueue);
        SkyActivity.SetEco(AppServices.Settings.Current.SkyQuality == SkyQuality.Eco);
        VisibilityChanged += (_, e) => SkyActivity.SetVisible(e.Visible && !IsMinimized());
        Activated += (_, e) => SkyActivity.SetActive(e.WindowActivationState != WindowActivationState.Deactivated);
        AppWindow.Changed += (_, e) =>
        {
            if (e.DidPresenterChange || e.DidSizeChange) SkyActivity.SetVisible(AppWindow.IsVisible && !IsMinimized());
        };

        ApplyTheme(AppServices.Settings.Current.ThemeMode);
        AppServices.SettingsChanged += s =>
        {
            ApplyTheme(s.ThemeMode);
            SkyActivity.SetEco(s.SkyQuality == SkyQuality.Eco);
        };

        Motion.FadeOnVisibility(LiveTimer, rise: 0);
        RightTools.SizeChanged += (_, _) => BalanceTabs();
        Root.SizeChanged += (_, _) => BalanceTabs();
        AddAccelerators();
        Go(Destination.Focus);

        // The focus heartbeat: completes due phases and keeps the title, taskbar and live timer in step.
        _taskbar = new TaskbarProgress(WindowNative.GetWindowHandle(this));
        _heartbeat = DispatcherQueue.CreateTimer();
        _heartbeat.Interval = TimeSpan.FromMilliseconds(500);
        _heartbeat.Tick += (_, _) => Beat();
        _heartbeat.Start();
        AppServices.Focus.Changed += _ => AppServices.OnUi(Beat);
        AppServices.Focus.PhaseCompleted += _ => AppServices.OnUi(Chime);
        Beat();

        Root.Loaded += async (_, _) =>
        {
            if (!AppServices.Settings.Current.OnboardingDone) await Dialogs.ShowOnboarding(Root.XamlRoot);
        };
    }

    private bool IsMinimized() => AppWindow.Presenter is OverlappedPresenter { State: OverlappedPresenterState.Minimized };

    public void ApplyTheme(ThemeMode mode)
    {
        Root.RequestedTheme = mode switch
        {
            ThemeMode.LIGHT => ElementTheme.Light,
            ThemeMode.DARK => ElementTheme.Dark,
            _ => ElementTheme.Default,
        };
        RefreshChrome();
    }

    // ---- Navigation ---------------------------------------------------------------------------

    public void Go(Destination destination)
    {
        if (_current == destination && ContentFrame.BackStack.Count == 0) return;
        // Slide in the direction of travel along the tabs, like a segmented control.
        var from = _current;
        _current = destination;
        NavigationTransitionInfo transition = from == null || !Motion.Enabled
            ? new SuppressNavigationTransitionInfo()
            : new SlideNavigationTransitionInfo
            {
                Effect = destination > from ? SlideNavigationTransitionEffect.FromRight : SlideNavigationTransitionEffect.FromLeft,
            };
        ContentFrame.Navigate(Destinations[destination], null, transition);
        ContentFrame.BackStack.Clear(); // top-level pages are peers, not a history
        RefreshChrome();
    }

    /// <summary>Opens a drill-in page (habit detail, edit, partner…) on top of the current one.</summary>
    public void Navigate(Type page, object? parameter = null)
    {
        if (ContentFrame.CurrentSourcePageType == page && Equals(parameter, _lastParameter)) return;
        _lastParameter = parameter;
        ContentFrame.Navigate(page, parameter, new DrillInNavigationTransitionInfo());
    }

    private object? _lastParameter;

    public void OpenHabit(long id) => Navigate(typeof(HabitDetailPage), id);

    public void OpenSettings()
    {
        if (ContentFrame.Content is SettingsPage) return;
        Navigate(typeof(SettingsPage));
    }

    public void OpenPartners()
    {
        if (ContentFrame.Content is PartnersPage) return;
        Navigate(typeof(PartnersPage));
    }

    public void OpenFollow(string code)
    {
        OpenPartners();
        if (ContentFrame.Content is PartnersPage page) page.OpenFollow(code);
    }

    public bool GoBack()
    {
        if (!ContentFrame.CanGoBack) return false;
        ContentFrame.GoBack();
        return true;
    }

    public void BringToFront()
    {
        if (AppWindow.Presenter is OverlappedPresenter { State: OverlappedPresenterState.Minimized } p) p.Restore();
        Activate();
        SetForegroundWindow(WindowNative.GetWindowHandle(this));
    }

    private void OnNavigated(object sender, NavigationEventArgs e) => RefreshChrome();

    private void OnTabChanged(SelectorBar sender, SelectorBarSelectionChangedEventArgs args)
    {
        if (_syncingTabs || sender.SelectedItem?.Tag is not string tag) return;
        var destination = Enum.Parse<Destination>(tag);
        if (ContentFrame.CanGoBack) _current = null; // leaving a drill-in page: always navigate
        Go(destination);
    }

    /// <summary>Back button, tab selection and title bar colours for whatever page is showing.</summary>
    private void RefreshChrome()
    {
        var drilledIn = ContentFrame.CanGoBack;
        AppTitleBar.IsBackButtonVisible = drilledIn;
        AppTitleBar.IsBackButtonEnabled = drilledIn;
        BalanceTabs();

        _syncingTabs = true;
        foreach (var item in Tabs.Items) item.IsSelected = !drilledIn && (string)item.Tag == _current?.ToString();
        _syncingTabs = false;

        // Over the sky the bar is always light-on-dark, whatever the app theme.
        var onSky = ContentFrame.Content is ISkyPage;
        AppTitleBar.RequestedTheme = onSky ? ElementTheme.Dark : ElementTheme.Default;
        UpdateCaptionButtons(onSky);
    }

    /// <summary>Pads the left of the title bar to match the right (tools + window buttons), centring the tabs.</summary>
    private void BalanceTabs()
    {
        var scale = Root.XamlRoot?.RasterizationScale ?? 1.0;
        var captions = AppWindow.TitleBar.RightInset / scale;
        var back = AppTitleBar.IsBackButtonVisible ? 44 : 0;
        LeftBalance.Width = Math.Max(0, RightTools.ActualWidth + 8 + captions - back);
    }

    private void OnBackRequested(TitleBar sender, object args) => GoBack();
    private void OnSettings(object sender, RoutedEventArgs e) => OpenSettings();
    private void OnPartners(object sender, RoutedEventArgs e) => OpenPartners();
    private void OnLiveTimer(object sender, RoutedEventArgs e) { _current = null; Go(Destination.Focus); }

    private void AddAccelerators()
    {
        void Add(VirtualKey key, VirtualKeyModifiers mods, Action action)
        {
            var a = new KeyboardAccelerator { Key = key, Modifiers = mods };
            a.Invoked += (_, args) => { action(); args.Handled = true; };
            Root.KeyboardAccelerators.Add(a);
        }
        Add(VirtualKey.Number1, VirtualKeyModifiers.Control, () => { _current = ContentFrame.CanGoBack ? null : _current; Go(Destination.Focus); });
        Add(VirtualKey.Number2, VirtualKeyModifiers.Control, () => { _current = ContentFrame.CanGoBack ? null : _current; Go(Destination.Progress); });
        Add(VirtualKey.Number3, VirtualKeyModifiers.Control, () => { _current = ContentFrame.CanGoBack ? null : _current; Go(Destination.Habits); });
        Add(VirtualKey.Number4, VirtualKeyModifiers.Control, OpenPartners);
        Add((VirtualKey)188, VirtualKeyModifiers.Control, OpenSettings); // Ctrl+,
        Add(VirtualKey.Escape, VirtualKeyModifiers.None, () => GoBack());
        Root.KeyboardAcceleratorPlacementMode = KeyboardAcceleratorPlacementMode.Hidden;
    }

    // ---- Focus heartbeat ----------------------------------------------------------------------

    private void Beat()
    {
        var focus = AppServices.Focus;
        var run = focus.Tick();
        var now = focus.Now;

        // A live timer in the title bar while a session runs and you're looking at something else.
        var showLive = run is { AwaitingStart: false } && ContentFrame.Content is not FocusPage;
        LiveTimer.Visibility = showLive ? Visibility.Visible : Visibility.Collapsed;
        if (showLive)
        {
            LiveTime.Text = TimeText.Clock(run!.RemainingMs(now));
            LiveDot.Fill = (Microsoft.UI.Xaml.Media.Brush)Application.Current.Resources[run.IsBreak ? "OnSkySecondaryBrush" : "SunriseBrush"];
            LiveDot.Opacity = run.Running ? 1 : 0.4;
            AutomationPropertiesHelper.SetName(LiveTimer, $"{(run.IsBreak ? "Break" : "Focus")}, {TimeText.Spoken(run.RemainingMs(now))} left");
        }

        // Time left in the window title shows on the taskbar thumbnail and in Alt+Tab.
        Title = run is { AwaitingStart: false }
            ? $"{TimeText.Clock(run.RemainingMs(now))} {(run.IsBreak ? "break" : "focus")} – RepDayrise"
            : "RepDayrise";
        _taskbar.Set(
            run == null || run.AwaitingStart ? TaskbarProgress.State.None : run.Running ? TaskbarProgress.State.Normal : TaskbarProgress.State.Paused,
            run?.Fraction(now) ?? 0);

        FocusHeartbeat?.Invoke(run);
    }

    /// <summary>
    /// The phase-end toast has its own sound, but Windows Focus mutes it; then a soft in-app chime still says the
    /// phase is over, as long as the app is running and sound is on.
    /// </summary>
    private void Chime()
    {
        if (!AppServices.Settings.Current.Focus.Sound || !QuietHours.SystemFocusActive) return;
        try
        {
            var file = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Windows), "Media", "Windows Notify Calendar.wav");
            if (!File.Exists(file)) return;
            _chime ??= new Windows.Media.Playback.MediaPlayer { Volume = 0.6 };
            _chime.Source = Windows.Media.Core.MediaSource.CreateFromUri(new Uri(file));
            _chime.Play();
        }
        catch (Exception e)
        {
            System.Diagnostics.Debug.WriteLine($"Chime failed: {e}");
        }
    }

    // ---- Caption buttons ----------------------------------------------------------------------

    private void UpdateCaptionButtons(bool onSky)
    {
        var tb = AppWindow.TitleBar;
        var dark = onSky || Root.ActualTheme == ElementTheme.Dark;
        var fg = dark ? Color.FromArgb(255, 255, 255, 255) : Color.FromArgb(255, 0x1B, 0x1B, 0x1F);
        tb.ButtonBackgroundColor = Color.FromArgb(0, 0, 0, 0);
        tb.ButtonInactiveBackgroundColor = Color.FromArgb(0, 0, 0, 0);
        tb.ButtonForegroundColor = fg;
        tb.ButtonInactiveForegroundColor = Color.FromArgb(140, fg.R, fg.G, fg.B);
        tb.ButtonHoverBackgroundColor = Color.FromArgb(28, fg.R, fg.G, fg.B);
        tb.ButtonHoverForegroundColor = fg;
        tb.ButtonPressedBackgroundColor = Color.FromArgb(48, fg.R, fg.G, fg.B);
        tb.ButtonPressedForegroundColor = fg;
    }

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetForegroundWindow(IntPtr hWnd);
}

internal static class AutomationPropertiesHelper
{
    public static void SetName(UIElement el, string name) => Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(el, name);
}
