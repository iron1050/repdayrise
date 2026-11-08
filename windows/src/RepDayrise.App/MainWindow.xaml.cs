using System.Runtime.InteropServices;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media.Animation;
using Microsoft.UI.Xaml.Navigation;
using RepDayrise.Core.Models;
using RepDayrise.Services;
using RepDayrise.Views;
using WinRT.Interop;

namespace RepDayrise;

public sealed partial class MainWindow : Window
{
    private static readonly Dictionary<string, Type> TopLevel = new()
    {
        ["today"] = typeof(TodayPage),
        ["history"] = typeof(HistoryPage),
        ["partners"] = typeof(PartnersPage),
        ["settings"] = typeof(SettingsPage),
    };

    private bool _syncingSelection;

    public MainWindow()
    {
        InitializeComponent();
        ExtendsContentIntoTitleBar = true;
        SetTitleBar(AppTitleBar);
        AppWindow.SetIcon("Assets/AppIcon.ico");
        AppWindow.Resize(new Windows.Graphics.SizeInt32(1180, 820));
        if (AppWindow.Presenter is OverlappedPresenter presenter)
        {
            presenter.PreferredMinimumWidth = 420;
            presenter.PreferredMinimumHeight = 520;
        }

        // The GPU sky only animates while it can be seen.
        Controls.Sky.SkyActivity.Initialize(DispatcherQueue);
        VisibilityChanged += (_, e) => Controls.Sky.SkyActivity.SetVisible(e.Visible && !IsMinimized());
        Activated += (_, e) => Controls.Sky.SkyActivity.SetActive(e.WindowActivationState != WindowActivationState.Deactivated);
        AppWindow.Changed += (_, e) =>
        {
            if (e.DidPresenterChange || e.DidSizeChange) Controls.Sky.SkyActivity.SetVisible(AppWindow.IsVisible && !IsMinimized());
        };

        ApplyTheme(AppServices.Settings.Current.ThemeMode);
        AppServices.SettingsChanged += s => ApplyTheme(s.ThemeMode);

        Nav.SelectedItem = Nav.MenuItems[0];
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
        AppWindow.TitleBar.PreferredTheme = mode switch
        {
            ThemeMode.LIGHT => TitleBarTheme.Light,
            ThemeMode.DARK => TitleBarTheme.Dark,
            _ => TitleBarTheme.UseDefaultAppMode,
        };
    }

    public void Navigate(Type page, object? parameter = null)
    {
        if (ContentFrame.CurrentSourcePageType == page && Equals(parameter, _lastParameter)) return;
        _lastParameter = parameter;
        ContentFrame.Navigate(page, parameter, new DrillInNavigationTransitionInfo());
    }

    private object? _lastParameter;

    public void OpenHabit(long id) => Navigate(typeof(HabitDetailPage), id);

    public void OpenFollow(string code)
    {
        SelectTopLevel("partners");
        if (ContentFrame.Content is PartnersPage page) page.OpenFollow(code);
    }

    public void BringToFront()
    {
        if (AppWindow.Presenter is OverlappedPresenter { State: OverlappedPresenterState.Minimized } p) p.Restore();
        Activate();
        SetForegroundWindow(WindowNative.GetWindowHandle(this));
    }

    private void SelectTopLevel(string tag)
    {
        Nav.SelectedItem = tag == "settings" ? Nav.SettingsItem : Nav.MenuItems.OfType<NavigationViewItem>().First(i => (string)i.Tag == tag);
    }

    private void OnNavSelectionChanged(NavigationView sender, NavigationViewSelectionChangedEventArgs args)
    {
        if (_syncingSelection) return;
        var tag = args.IsSettingsSelected ? "settings" : (args.SelectedItem as NavigationViewItem)?.Tag as string;
        if (tag == null || !TopLevel.TryGetValue(tag, out var page)) return;
        _lastParameter = null;
        ContentFrame.Navigate(page, null, new EntranceNavigationTransitionInfo());
        ContentFrame.BackStack.Clear(); // top-level pages are peers, not a history
        AppTitleBar.IsBackButtonEnabled = false;
    }

    private void OnNavigated(object sender, NavigationEventArgs e)
    {
        AppTitleBar.IsBackButtonEnabled = ContentFrame.CanGoBack;
        // Keep the pane selection in step when Back returns to a top-level page.
        var tag = TopLevel.FirstOrDefault(kv => kv.Value == e.SourcePageType).Key;
        if (tag == null) return;
        _syncingSelection = true;
        SelectTopLevel(tag);
        _syncingSelection = false;
    }

    private void OnBackRequested(TitleBar sender, object args)
    {
        if (ContentFrame.CanGoBack) ContentFrame.GoBack();
    }

    private void OnPaneToggleRequested(TitleBar sender, object args) => Nav.IsPaneOpen = !Nav.IsPaneOpen;

    [DllImport("user32.dll")]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool SetForegroundWindow(IntPtr hWnd);
}
