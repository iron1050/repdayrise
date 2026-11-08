using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.Windows.AppLifecycle;

namespace RepDayrise;

/// <summary>
/// Custom entry point so RepDayrise runs as a single instance: a second launch (Start, an invite link, a reminder)
/// hands its activation to the running window instead of opening another one.
/// </summary>
public static class Program
{
    [STAThread]
    private static int Main(string[] args)
    {
        WinRT.ComWrappersSupport.InitializeComWrappers();

        var activation = AppInstance.GetCurrent().GetActivatedEventArgs();
        var main = AppInstance.FindOrRegisterForKey("main");
        if (!main.IsCurrent)
        {
            Task.Run(async () => await main.RedirectActivationToAsync(activation)).Wait();
            return 0;
        }

        main.Activated += (_, e) => App.Current?.HandleActivation(e);

        Application.Start(p =>
        {
            SynchronizationContext.SetSynchronizationContext(new DispatcherQueueSynchronizationContext(DispatcherQueue.GetForCurrentThread()));
            _ = new App();
        });
        return 0;
    }
}
