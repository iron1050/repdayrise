using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using RepDayrise.Core.Models;

namespace RepDayrise.Controls;

/// <summary>The three top-level destinations reachable from the floating bar.</summary>
public enum Destination { Focus, Progress, Habits }

/// <summary>The floating bottom bar: Progress · Focus pill · Habits. The pill shows the live countdown.</summary>
public sealed partial class FloatingBar : UserControl
{
    public event Action<Destination>? Navigate;

    public FloatingBar()
    {
        InitializeComponent();
    }

    public void SetSelected(Destination? selected)
    {
        Highlight(ProgressButton, selected == Destination.Progress);
        Highlight(HabitsButton, selected == Destination.Habits);
        Highlight(FocusPill, selected == Destination.Focus);
    }

    /// <summary>Shows the running phase and its time left on the pill, or plain "Focus" when idle.</summary>
    public void ShowRun(FocusRun? run, long now)
    {
        if (run == null)
        {
            PillLabel.Text = "Focus";
            PillTime.Visibility = Visibility.Collapsed;
            LiveDot.Visibility = Visibility.Collapsed;
            PillIcon.Visibility = Visibility.Visible;
            return;
        }
        PillLabel.Text = run.AwaitingStart ? (run.IsBreak ? "Break ready" : "Focus ready") : run.IsBreak ? "Break" : "Focus";
        PillTime.Text = run.AwaitingStart ? "" : FormatClock(run.RemainingMs(now));
        PillTime.Visibility = run.AwaitingStart ? Visibility.Collapsed : Visibility.Visible;
        LiveDot.Visibility = run.Running ? Visibility.Visible : Visibility.Collapsed;
        LiveDot.Fill = run.IsBreak ? new SolidColorBrush(Windows.UI.Color.FromArgb(255, 0x4F, 0xA3, 0xE3)) : (Brush)Application.Current.Resources["SunriseBrush"];
        PillIcon.Visibility = run.Running ? Visibility.Collapsed : Visibility.Visible;
        PillIcon.Glyph = run.AwaitingStart ? "" : ""; // play when waiting, pause glyph when paused
    }

    /// <summary>m:ss, or h:mm:ss from an hour.</summary>
    public static string FormatClock(long ms)
    {
        var total = (long)Math.Ceiling(ms / 1000.0);
        var h = total / 3600;
        var m = total % 3600 / 60;
        var s = total % 60;
        return h > 0 ? $"{h}:{m:00}:{s:00}" : $"{m}:{s:00}";
    }

    /// <summary>The current destination reads in the accent colour; the rest keep their theme colours from the style.</summary>
    private static void Highlight(Button b, bool on)
    {
        if (on) b.Foreground = (Brush)Application.Current.Resources["SunriseBrush"];
        else b.ClearValue(ForegroundProperty);
    }

    private void OnProgress(object sender, RoutedEventArgs e) => Navigate?.Invoke(Destination.Progress);
    private void OnFocus(object sender, RoutedEventArgs e) => Navigate?.Invoke(Destination.Focus);
    private void OnHabits(object sender, RoutedEventArgs e) => Navigate?.Invoke(Destination.Habits);
}
