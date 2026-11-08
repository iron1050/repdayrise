using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.Windows.AppLifecycle;
using Microsoft.Windows.AppNotifications;
using RepDayrise.Services;
using Windows.ApplicationModel.Activation;

namespace RepDayrise;

public partial class App : Application
{
    private DispatcherQueueTimer? _partnerRefresh;

    public static new App? Current => Application.Current as App;

    public MainWindow? Window { get; private set; }

    public App()
    {
        InitializeComponent();
    }

    protected override void OnLaunched(Microsoft.UI.Xaml.LaunchActivatedEventArgs args)
    {
        var ui = DispatcherQueue.GetForCurrentThread();
        AppServices.Initialize(ui);

        // Reminder clicks while the app is running arrive here; a click that launches the app arrives as an activation.
        AppNotificationManager.Default.NotificationInvoked += (_, e) => AppServices.OnUi(() => HandleArgument(e.Argument));
        try { AppNotificationManager.Default.Register(); } catch { /* notifications unavailable */ }

        // Catch up a focus run that ended while the app was closed, before any toast button acts on it.
        AppServices.Focus.Tick();

        Window = new MainWindow();
        Window.Activate();

        HandleActivation(AppInstance.GetCurrent().GetActivatedEventArgs());

        AppServices.Reminders.RequestReschedule();
        _ = AppServices.Sharing.RefreshFollowing();
        _partnerRefresh = ui.CreateTimer();
        _partnerRefresh.Interval = TimeSpan.FromMinutes(15);
        _partnerRefresh.Tick += (_, _) =>
        {
            _ = AppServices.Sharing.RefreshFollowing();
            AppServices.Sharing.RequestSync(); // republishes after midnight so partners see the new day
        };
        _partnerRefresh.Start();
    }

    /// <summary>Handles a launch, invite link or reminder click, from this process or redirected from a second one.</summary>
    public void HandleActivation(AppActivationArguments e)
    {
        AppServices.OnUi(() =>
        {
            switch (e.Kind)
            {
                case ExtendedActivationKind.Protocol when e.Data is IProtocolActivatedEventArgs p:
                    HandleUri(p.Uri);
                    break;
                case ExtendedActivationKind.AppNotification when e.Data is AppNotificationActivatedEventArgs n:
                    HandleArgument(n.Argument);
                    break;
                case ExtendedActivationKind.ToastNotification when e.Data is IToastNotificationActivatedEventArgs t:
                    HandleArgument(t.Argument);
                    break;
                case ExtendedActivationKind.Launch when e.Data is ILaunchActivatedEventArgs l:
                    HandleArgument(l.Arguments);
                    break;
            }
            Window?.BringToFront();
        });
    }

    private void HandleUri(Uri uri)
    {
        // dayrise://join/CODE, as in the Android app's invite links.
        if (uri.Host.Equals("join", StringComparison.OrdinalIgnoreCase))
            Window?.OpenFollow(Core.Sharing.SharingService.ExtractCode(uri.ToString()));
    }

    private void HandleArgument(string? argument)
    {
        if (string.IsNullOrEmpty(argument)) return;
        if (argument.StartsWith("habit=", StringComparison.Ordinal) && long.TryParse(argument.AsSpan(6), out var id))
            Window?.OpenHabit(id);
        else if (argument.StartsWith("focus=", StringComparison.Ordinal))
            HandleFocusAction(argument[6..]);
    }

    /// <summary>Buttons on the focus phase-end notification.</summary>
    private void HandleFocusAction(string action)
    {
        var focus = AppServices.Focus;
        focus.Tick(); // make sure the phase that just ended is recorded first
        switch (action)
        {
            case "resume": focus.Resume(); break;
            case "pause": focus.Pause(); break;
            case "skip": focus.Skip(); break;
            case "stop": focus.Stop(); break;
            case "extend5": focus.Extend(TimeSpan.FromMinutes(5)); break;
        }
        Window?.Go(Controls.Destination.Focus);
    }
}
