using System.Runtime.InteropServices;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media.Animation;
using Microsoft.UI.Xaml.Navigation;
using RepDayrise.Controls;
using RepDayrise.Controls.Sky;
using RepDayrise.Core.Data;
using RepDayrise.Core.Models;
using RepDayrise.Services;
using RepDayrise.Views;
using Windows.System;
using Windows.UI;
using WinRT.Interop;

namespace RepDayrise;

/// <summary>Pages drawn over the dark sky; the window's caption buttons turn white on them.</summary>
public interface ISkyPage;

public sealed partial class MainWindow : Window
{
    private static readonly Dictionary<Destination, Type> Destinations = new()
    {
        [Destination.Focus] = typeof(TodayPage),
        [Destination.Progress] = typeof(HistoryPage),
        [Destination.Habits] = typeof(TodayPage),
    };

    private Destination? _current;

    public MainWindow()
    {
        InitializeComponent();
        ExtendsContentIntoTitleBar = true;
        AppWindow.TitleBar.PreferredHeightOption = TitleBarHeightOption.Tall;
        SetTitleBar(DragRegion);
        AppWindow.SetIcon("Assets/AppIcon.ico");
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1180, 820));
        if (AppWindow.Presenter is OverlappedPresenter presenter)
        {
            presenter.PreferredMinimumWidth = 460;
            presenter.PreferredMinimumHeight = 560;
        }
        TopChrome.SizeChanged += (_, _) => DragRegion.Margin = new Thickness(TopChrome.ActualWidth + 24, 0, 0, 0);

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

        Bar.Navigate += Go;
        AddAccelerators();
        Go(Destination.Focus);

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
        UpdateCaptionButtons();
    }

    // ---- Navigation ---------------------------------------------------------------------------

    public void Go(Destination destination)
    {
        if (_current == destination && ContentFrame.BackStack.Count == 0) return;
        _current = destination;
        ContentFrame.Navigate(Destinations[destination], null, new EntranceNavigationTransitionInfo());
        ContentFrame.BackStack.Clear(); // top-level pages are peers, not a history
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

    private void OnNavigated(object sender, NavigationEventArgs e)
    {
        BackButton.Visibility = ContentFrame.CanGoBack ? Visibility.Visible : Visibility.Collapsed;
        var top = Destinations.Where(kv => kv.Value == e.SourcePageType).Select(kv => (Destination?)kv.Key).FirstOrDefault();
        if (ContentFrame.BackStack.Count == 0 && top != null && _current != null) top = _current;
        Bar.SetSelected(ContentFrame.CanGoBack ? null : top);
        UpdateCaptionButtons();
    }

    private void OnBack(object sender, RoutedEventArgs e) => GoBack();
    private void OnSettings(object sender, RoutedEventArgs e) => OpenSettings();
    private void OnPartners(object sender, RoutedEventArgs e) => OpenPartners();

    private void AddAccelerators()
    {
        void Add(VirtualKey key, VirtualKeyModifiers mods, Action action)
        {
            var a = new KeyboardAccelerator { Key = key, Modifiers = mods };
            a.Invoked += (_, args) => { action(); args.Handled = true; };
            Root.KeyboardAccelerators.Add(a);
        }
        Add(VirtualKey.Number1, VirtualKeyModifiers.Control, () => Go(Destination.Focus));
        Add(VirtualKey.Number2, VirtualKeyModifiers.Control, () => Go(Destination.Progress));
        Add(VirtualKey.Number3, VirtualKeyModifiers.Control, () => Go(Destination.Habits));
        Add(VirtualKey.Number4, VirtualKeyModifiers.Control, OpenPartners);
        Add((VirtualKey)188, VirtualKeyModifiers.Control, OpenSettings); // Ctrl+,
        Add(VirtualKey.Escape, VirtualKeyModifiers.None, () => GoBack());
        Root.KeyboardAcceleratorPlacementMode = KeyboardAcceleratorPlacementMode.Hidden;
    }

    // ---- Caption buttons ----------------------------------------------------------------------

    /// <summary>White caption glyphs over the sky; theme-coloured elsewhere.</summary>
    private void UpdateCaptionButtons()
    {
        var tb = AppWindow.TitleBar;
        var dark = Root.ActualTheme == ElementTheme.Dark;
        var onSky = ContentFrame.Content is ISkyPage;
        var fg = onSky || dark ? Color.FromArgb(255, 255, 255, 255) : Color.FromArgb(255, 0x1A, 0x1B, 0x2E);
        tb.ButtonBackgroundColor = Color.FromArgb(0, 0, 0, 0);
        tb.ButtonInactiveBackgroundColor = Color.FromArgb(0, 0, 0, 0);
        tb.ButtonForegroundColor = fg;
        tb.ButtonInactiveForegroundColor = Color.FromArgb(140, fg.R, fg.G, fg.B);
        tb.ButtonHoverBackgroundColor = Color.FromArgb(40, fg.R, fg.G, fg.B);
        tb.ButtonHoverForegroundColor = fg;
        tb.ButtonPressedBackgroundColor = Color.FromArgb(64, fg.R, fg.G, fg.B);
        tb.ButtonPressedForegroundColor = fg;
    }

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetForegroundWindow(IntPtr hWnd);
}
