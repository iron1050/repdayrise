using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Navigation;
using Microsoft.UI.Xaml.Shapes;
using RepDayrise.Controls;
using RepDayrise.Core.Domain;
using RepDayrise.Core.Models;
using RepDayrise.Services;
using Windows.System;

namespace RepDayrise.Views;

/// <summary>The focus timer over the full-window sky.</summary>
public sealed partial class FocusPage : Page, ISkyPage
{
    private static readonly int[] Presets = [25, 50, 90];

    private FocusSummary _summary = new(0, 0, 0, 0, 0, 0, 0);
    private IReadOnlyList<Habit> _timerHabits = [];
    private FocusRun? _run;

    public FocusPage()
    {
        InitializeComponent();
        SizeChanged += (_, _) => FitDial();

        var space = new KeyboardAccelerator { Key = VirtualKey.Space };
        space.Invoked += (_, e) =>
        {
            if (FocusManager.GetFocusedElement(XamlRoot) is TextBox or NumberBox) return;
            OnPrimary(this, new RoutedEventArgs());
            e.Handled = true;
        };
        KeyboardAccelerators.Add(space);
        KeyboardAcceleratorPlacementMode = KeyboardAcceleratorPlacementMode.Hidden;
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        AppServices.DataChanged += OnDataChanged;
        AppServices.SettingsChanged += OnSettingsChanged;
        QuietHours.Changed += OnQuietChanged;
        MainWindow.FocusHeartbeat += OnHeartbeat;
        LoadHabits();
        LoadSummary();
        ShowSettings();
        Render(AppServices.Focus.Current);
    }

    protected override void OnNavigatedFrom(NavigationEventArgs e)
    {
        AppServices.DataChanged -= OnDataChanged;
        AppServices.SettingsChanged -= OnSettingsChanged;
        QuietHours.Changed -= OnQuietChanged;
        MainWindow.FocusHeartbeat -= OnHeartbeat;
    }

    private void OnDataChanged()
    {
        LoadHabits();
        LoadSummary();
        Render(AppServices.Focus.Current);
    }

    private void OnSettingsChanged(Core.Data.Settings s)
    {
        LoadSummary();
        ShowSettings();
        Render(_run);
    }

    private void OnQuietChanged() => DispatcherQueue.TryEnqueue(() => Render(_run));

    private void OnHeartbeat(FocusRun? run) => Render(run);

    // ---- Data ---------------------------------------------------------------------------------

    private void LoadHabits()
    {
        _timerHabits = AppServices.Store.GetAllHabits().Where(h => h.Type == HabitType.TIMER && !h.Archived).ToList();
        HabitMenu.Items.Clear();
        var none = new MenuFlyoutItem { Text = "No habit" };
        none.Click += (_, _) => PickHabit(null);
        HabitMenu.Items.Add(none);
        if (_timerHabits.Count > 0) HabitMenu.Items.Add(new MenuFlyoutSeparator());
        foreach (var h in _timerHabits)
        {
            var item = new MenuFlyoutItem
            {
                Text = h.Name,
                Icon = new FontIcon { Glyph = HabitVisuals.Glyph(h.Icon), Foreground = HabitVisuals.BrushOf(h.ColorIndex) },
            };
            var habit = h;
            item.Click += (_, _) => PickHabit(habit);
            HabitMenu.Items.Add(item);
        }
        HabitMenu.Items.Add(new MenuFlyoutSeparator());
        var create = new MenuFlyoutItem { Text = "New timer habit…", Icon = new FontIcon { Glyph = "" } };
        create.Click += (_, _) => App.Current?.Window?.Navigate(typeof(EditHabitPage), HabitType.TIMER);
        HabitMenu.Items.Add(create);
    }

    private void LoadSummary()
    {
        var today = EpochDay.Today();
        var sessions = AppServices.Store.GetFocusSessions(today.AddDays(-400), today);
        _summary = FocusStats.Summary(sessions, today, AppServices.Logic, AppServices.Settings.Current.Focus.DailyGoalMinutes);
    }

    private Habit? SelectedHabit()
    {
        if (_run != null) return _run.HabitId is long id ? _timerHabits.FirstOrDefault(h => h.Id == id) ?? AppServices.Store.GetHabit(id) : null;
        var uid = AppServices.Settings.Current.Focus.LastHabitUid;
        return uid == null ? null : _timerHabits.FirstOrDefault(h => h.Uid == uid);
    }

    private void PickHabit(Habit? habit)
    {
        AppServices.Settings.Update(s => s with { Focus = s.Focus with { LastHabitUid = habit?.Uid } });
        if (_run != null) AppServices.Focus.SetHabit(habit?.Id);
        Render(AppServices.Focus.Current);
    }

    // ---- Settings-driven controls --------------------------------------------------------------

    private void ShowSettings()
    {
        var f = AppServices.Settings.Current.Focus;
        var preset = Presets.Contains(f.FocusMinutes);
        Chip25.IsChecked = f.FocusMinutes == 25;
        Chip50.IsChecked = f.FocusMinutes == 50;
        Chip90.IsChecked = f.FocusMinutes == 90;
        ChipCustom.IsChecked = !preset;
        ChipCustom.Content = preset ? "Custom" : $"{f.FocusMinutes} min";
        BreaksChip.IsChecked = f.BreaksEnabled;
        BreaksLabel.Text = f.BreaksEnabled ? $"Breaks · {f.ShortBreakMinutes}/{f.LongBreakMinutes}" : "No breaks";
    }

    private void OnLengthChip(object sender, RoutedEventArgs e)
    {
        var minutes = int.Parse((string)((FrameworkElement)sender).Tag);
        AppServices.Settings.Update(s => s with { Focus = s.Focus with { FocusMinutes = minutes } });
    }

    private void OnCustomChip(object sender, RoutedEventArgs e)
    {
        ShowSettings(); // keep the chip states honest until a length is chosen
        CustomMinutes.Value = AppServices.Settings.Current.Focus.CustomMinutes;
        FlyoutBase.ShowAttachedFlyout(ChipCustom);
    }

    private void OnCustomApply(object sender, RoutedEventArgs e)
    {
        if (double.IsNaN(CustomMinutes.Value)) return;
        var minutes = (int)Math.Clamp(Math.Round(CustomMinutes.Value), 1, 600);
        AppServices.Settings.Update(s => s with { Focus = s.Focus with { FocusMinutes = minutes, CustomMinutes = minutes } });
        CustomFlyout.Hide();
    }

    private void OnBreaksChip(object sender, RoutedEventArgs e) =>
        AppServices.Settings.Update(s => s with { Focus = s.Focus with { BreaksEnabled = BreaksChip.IsChecked == true } });

    // ---- Actions ------------------------------------------------------------------------------

    private void OnPrimary(object sender, RoutedEventArgs e)
    {
        var focus = AppServices.Focus;
        if (focus.Current == null)
        {
            var prefs = AppServices.Settings.Current.Focus;
            focus.Start(prefs.ToConfig(), SelectedHabit()?.Id);
        }
        else
        {
            focus.Toggle();
        }
        Render(focus.Current);
    }

    private void OnSkip(object sender, RoutedEventArgs e) => Render(AppServices.Focus.Skip());

    private void OnStop(object sender, RoutedEventArgs e) => Render(AppServices.Focus.Stop());

    private async void OnQuietChip(object sender, RoutedEventArgs e) => await QuietHours.OpenSystemSettings();

    // ---- Rendering ----------------------------------------------------------------------------

    private void Render(FocusRun? run)
    {
        _run = run;
        var now = AppServices.Focus.Now;
        var prefs = AppServices.Settings.Current.Focus;
        var today = EpochDay.Today();

        DayRelative.Text = "Today";
        DayDate.Text = ", " + DateTime.Now.ToString("d MMM");

        var idle = run == null;
        LengthChips.Visibility = idle ? Visibility.Visible : Visibility.Collapsed;
        BreaksChip.Visibility = idle ? Visibility.Visible : Visibility.Collapsed;
        StopButton.Visibility = idle ? Visibility.Collapsed : Visibility.Visible;
        SkipButton.Visibility = idle ? Visibility.Collapsed : Visibility.Visible;

        var habit = SelectedHabit();
        HabitLabel.Text = habit?.Name ?? "No habit";
        HabitSwatch.Background = habit != null ? HabitVisuals.BrushOf(habit.ColorIndex) : new SolidColorBrush(Windows.UI.Color.FromArgb(102, 255, 255, 255));
        HabitButton.Visibility = idle || run!.Phase == FocusPhase.FOCUS || run.AwaitingStart ? Visibility.Visible : Visibility.Collapsed;

        // Live focus counts toward today's total as it happens.
        var live = run is { Phase: FocusPhase.FOCUS, AwaitingStart: false } ? run.ElapsedMs(now) / 60000.0 : 0;
        var todayMinutes = _summary.TodayMinutes + live;
        TodayMinutes.Text = Format.Duration(todayMinutes);
        var streak = _summary.CurrentStreak + (_summary.TodayMinutes < prefs.DailyGoalMinutes && todayMinutes >= prefs.DailyGoalMinutes ? 1 : 0);
        StreakText.Text = streak == 1 ? "1 day" : $"{streak} days";
        RenderDots(run, prefs.LongBreakEvery);

        if (idle)
        {
            PhaseLabel.Text = "READY TO FOCUS";
            Countdown.Text = FloatingBar.FormatClock(prefs.FocusMinutes * 60_000L);
            SubLabel.Text = habit != null ? $"toward {habit.Name}" : $"{Minutes(prefs.FocusMinutes * 60_000L)} of focus";
            Dial.Value = 0;
            PrimaryLabel.Text = "Start focus";
            PrimaryIcon.Glyph = "";
            Sky.Progress = Math.Clamp(todayMinutes / Math.Max(prefs.DailyGoalMinutes, 1), 0, 1);
        }
        else
        {
            var r = run!;
            var fraction = r.AwaitingStart ? 0 : r.Fraction(now);
            var every = Math.Max(r.Config.LongBreakEvery, 1);
            var nth = r.CompletedFocusCount % every + 1;
            PhaseLabel.Text = (r.Phase, r.AwaitingStart) switch
            {
                (FocusPhase.FOCUS, true) => "UP NEXT · FOCUS",
                (FocusPhase.FOCUS, _) when r.Bonus => "FOCUS · BONUS",
                (FocusPhase.FOCUS, _) when r.Config.BreaksEnabled => $"FOCUS · {nth} OF {every}",
                (FocusPhase.FOCUS, _) => "FOCUS",
                (FocusPhase.SHORT_BREAK, true) => "UP NEXT · SHORT BREAK",
                (FocusPhase.LONG_BREAK, true) => "UP NEXT · LONG BREAK",
                (FocusPhase.SHORT_BREAK, _) => "SHORT BREAK",
                _ => "LONG BREAK",
            };
            if (!r.Running && !r.AwaitingStart) PhaseLabel.Text = "PAUSED · " + PhaseLabel.Text;
            Countdown.Text = FloatingBar.FormatClock(r.AwaitingStart ? r.PhaseDurationMs : r.RemainingMs(now));
            SubLabel.Text = r.IsBreak
                ? "Stand up, stretch, look away from the screen"
                : habit != null ? $"toward {habit.Name}" : $"of {Minutes(r.PhaseDurationMs)}";
            Dial.Value = fraction;
            Dial.Opacity = r.Running || r.AwaitingStart ? 1 : 0.6;
            PrimaryLabel.Text = r.AwaitingStart ? (r.IsBreak ? "Start break" : "Start focus") : r.Running ? "Pause" : "Resume";
            PrimaryIcon.Glyph = r.Running ? "" : "";
            SkipButton.SetValue(ToolTipService.ToolTipProperty, r.IsBreak ? "Skip the break" : "Skip to the break");
            // The sun climbs through a focus phase; a break is full daylight.
            Sky.Progress = r.IsBreak ? 1 : fraction;
        }
        Sky.Date = today;
        // Buttons whose content is a panel need explicit names for screen readers.
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(PrimaryButton, PrimaryLabel.Text);
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(HabitButton, $"Habit: {HabitLabel.Text}");
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(QuietChip, QuietText.Text);

        RenderQuiet(run);
    }

    private void RenderDots(FocusRun? run, int every)
    {
        every = Math.Clamp(run?.Config.LongBreakEvery ?? every, 1, 8);
        var showDots = (run?.Config.BreaksEnabled ?? AppServices.Settings.Current.Focus.BreaksEnabled);
        CycleDots.Visibility = showDots ? Visibility.Visible : Visibility.Collapsed;
        var count = run?.CompletedFocusCount ?? 0;
        var filled = count > 0 && count % every == 0 && run?.Phase == FocusPhase.LONG_BREAK ? every : count % every;
        if (CycleDots.Children.Count != every)
        {
            CycleDots.Children.Clear();
            for (var i = 0; i < every; i++) CycleDots.Children.Add(new Ellipse { Width = 8, Height = 8 });
        }
        for (var i = 0; i < every; i++)
            ((Ellipse)CycleDots.Children[i]).Fill = new SolidColorBrush(i < filled
                ? Windows.UI.Color.FromArgb(255, 0xFF, 0xB0, 0x67)
                : Windows.UI.Color.FromArgb(70, 255, 255, 255));
    }

    private void RenderQuiet(FocusRun? run)
    {
        if (QuietHours.SystemFocusActive)
        {
            QuietIcon.Glyph = "";
            QuietText.Text = "Do not disturb is on";
            QuietChip.Opacity = 1;
            return;
        }
        QuietIcon.Glyph = "";
        QuietText.Text = run is { Phase: FocusPhase.FOCUS, Running: true } ? "Silence notifications" : "Do not disturb";
        QuietChip.Opacity = run is { Phase: FocusPhase.FOCUS, Running: true } ? 1 : 0.75;
        ToolTipService.SetToolTip(QuietChip, AppServices.Settings.Current.Focus.QuietReminders
            ? "RepDayrise holds its own reminders while you focus. Click to turn on Windows Focus for everything else."
            : "Open Windows Focus settings to silence notifications");
    }

    private static string Minutes(long ms)
    {
        var m = (int)Math.Round(ms / 60000.0);
        return m == 1 ? "1 minute" : $"{m} minutes";
    }

    private void FitDial()
    {
        // Leave room for the label, chips, buttons and the floating bar.
        var size = Math.Clamp(Math.Min(ActualWidth * 0.6, ActualHeight - 420), 170, 340);
        DialHost.Width = DialHost.Height = size;
        Countdown.FontSize = size * 0.25;
    }
}
