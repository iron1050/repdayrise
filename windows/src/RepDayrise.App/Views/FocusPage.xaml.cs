using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Input;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Navigation;
using Microsoft.UI.Xaml.Shapes;
using RepDayrise.Core.Domain;
using RepDayrise.Core.Models;
using RepDayrise.Services;
using Windows.System;
using Windows.UI;
using Colors = Microsoft.UI.Colors;

namespace RepDayrise.Views;

/// <summary>The focus timer over the full-window sky.</summary>
public sealed partial class FocusPage : Page, ISkyPage
{
    private static readonly int[] Presets = [25, 50, 90];

    private FocusSummary _summary = new(0, 0, 0, 0, 0, 0, 0);
    private IReadOnlyList<Habit> _timerHabits = [];
    private FocusRun? _run;
    private string _shape = ""; // which controls are showing, so they only animate when that changes

    public FocusPage()
    {
        InitializeComponent();
        SizeChanged += (_, _) => FitTimer();
        LengthPicker.SetItems(["25 min", "50 min", "90 min", "Custom"]);
        LengthPicker.ItemInvoked += OnLength;

        foreach (var el in new UIElement[] { LengthPicker, BreaksButton, SkipButton, StopButton, HabitButton }) Motion.FadeOnVisibility(el);

        var space = new KeyboardAccelerator { Key = VirtualKey.Space };
        space.Invoked += (_, e) =>
        {
            if (FocusManager.GetFocusedElement(XamlRoot) is TextBox or NumberBox or Button) return;
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
        MainWindow.FocusHeartbeat += Render;
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
        MainWindow.FocusHeartbeat -= Render;
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

    // ---- Data ---------------------------------------------------------------------------------

    private void LoadHabits()
    {
        _timerHabits = AppServices.Store.GetAllHabits().Where(h => h.Type == HabitType.TIMER && !h.Archived).ToList();
        HabitMenu.Items.Clear();
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
        if (_timerHabits.Count > 0) HabitMenu.Items.Add(new MenuFlyoutSeparator());
        var none = new MenuFlyoutItem { Text = "Don't count toward a habit" };
        none.Click += (_, _) => PickHabit(null);
        HabitMenu.Items.Add(none);
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
        var preset = Array.IndexOf(Presets, f.FocusMinutes);
        LengthPicker.SetLabel(3, preset >= 0 ? "Custom" : $"{f.FocusMinutes} min");
        LengthPicker.Select(preset >= 0 ? preset : 3);
        BreaksLabel.Text = f.BreaksEnabled ? "Breaks on" : "Breaks off";
        AutomationProperties.SetName(BreaksButton, f.BreaksEnabled ? "Breaks on. Turn off breaks" : "Breaks off. Turn on breaks");
    }

    private void OnLength(int index)
    {
        if (index < Presets.Length)
        {
            var minutes = Presets[index];
            AppServices.Settings.Update(s => s with { Focus = s.Focus with { FocusMinutes = minutes } });
            return;
        }
        var flyout = (Flyout)Resources["CustomFlyout"];
        CustomMinutes.Value = AppServices.Settings.Current.Focus.CustomMinutes;
        flyout.ShowAt(LengthPicker.ButtonAt(3));
    }

    private void OnCustomApply(object sender, RoutedEventArgs e)
    {
        if (double.IsNaN(CustomMinutes.Value)) return;
        var minutes = (int)Math.Clamp(Math.Round(CustomMinutes.Value), 1, 600);
        AppServices.Settings.Update(s => s with { Focus = s.Focus with { FocusMinutes = minutes, CustomMinutes = minutes } });
        ((Flyout)Resources["CustomFlyout"]).Hide();
    }

    private void OnBreaks(object sender, RoutedEventArgs e) =>
        AppServices.Settings.Update(s => s with { Focus = s.Focus with { BreaksEnabled = !s.Focus.BreaksEnabled } });

    // ---- Actions ------------------------------------------------------------------------------

    private void OnPrimary(object sender, RoutedEventArgs e)
    {
        var focus = AppServices.Focus;
        if (focus.Current == null) focus.Start(AppServices.Settings.Current.Focus.ToConfig(), SelectedHabit()?.Id);
        else focus.Toggle();
        Render(focus.Current);
    }

    private void OnSkip(object sender, RoutedEventArgs e) => Render(AppServices.Focus.Skip());

    private void OnStop(object sender, RoutedEventArgs e) => Render(AppServices.Focus.Stop());

    private async void OnQuiet(object sender, RoutedEventArgs e) => await QuietHours.OpenSystemSettings();

    // ---- Rendering ----------------------------------------------------------------------------

    private void Render(FocusRun? run)
    {
        _run = run;
        var now = AppServices.Focus.Now;
        var prefs = AppServices.Settings.Current.Focus;
        var idle = run == null;
        var habit = SelectedHabit();

        // Which controls show. Only touch Visibility when this changes, so the fades play once.
        var shape = idle ? "idle" : run!.AwaitingStart ? "waiting" : run.IsBreak ? "break" : "focus";
        if (shape != _shape)
        {
            _shape = shape;
            LengthPicker.Visibility = idle ? Visibility.Visible : Visibility.Collapsed;
            BreaksButton.Visibility = idle ? Visibility.Visible : Visibility.Collapsed;
            SkipButton.Visibility = idle ? Visibility.Collapsed : Visibility.Visible;
            StopButton.Visibility = idle ? Visibility.Collapsed : Visibility.Visible;
            HabitButton.Visibility = idle || run!.Phase == FocusPhase.FOCUS ? Visibility.Visible : Visibility.Collapsed;
        }

        HabitLabel.Text = habit?.Name ?? (idle ? "Choose a habit" : "Not counting toward a habit");
        HabitDot.Fill = habit != null ? HabitVisuals.BrushOf(habit.ColorIndex) : new SolidColorBrush(Color.FromArgb(120, 255, 255, 255));
        AutomationProperties.SetName(HabitButton, habit != null ? $"Counting toward {habit.Name}. Change habit" : "Choose a habit to count toward");

        // Today, with the session in progress counted live.
        var live = run is { Phase: FocusPhase.FOCUS, AwaitingStart: false } ? run.ElapsedMs(now) / 60000.0 : 0;
        var todayMinutes = _summary.TodayMinutes + live;
        TodayMinutes.Text = $"{TimeText.Short(todayMinutes)} today";
        var streak = _summary.CurrentStreak + (_summary.TodayMinutes < prefs.DailyGoalMinutes && todayMinutes >= prefs.DailyGoalMinutes ? 1 : 0);
        StreakText.Text = streak switch { 0 => "", 1 => "1-day streak", _ => $"{streak}-day streak" };
        RenderDots(run, prefs);

        string primary;
        if (idle)
        {
            Countdown.Text = TimeText.Clock(prefs.FocusMinutes * 60_000L);
            Status.Text = TimeText.Minutes(prefs.FocusMinutes) + (prefs.BreaksEnabled ? $", then a {prefs.ShortBreakMinutes}-minute break" : "");
            primary = "Start focus";
            Sky.Progress = Math.Clamp(todayMinutes / Math.Max(prefs.DailyGoalMinutes, 1), 0, 1);
        }
        else
        {
            var r = run!;
            var every = Math.Max(r.Config.LongBreakEvery, 1);
            var nth = r.CompletedFocusCount % every + 1;
            Countdown.Text = TimeText.Clock(r.AwaitingStart ? r.PhaseDurationMs : r.RemainingMs(now));
            Status.Text = (r.Phase, r.AwaitingStart, r.Running) switch
            {
                (FocusPhase.FOCUS, true, _) => "Ready for the next session",
                (FocusPhase.SHORT_BREAK, true, _) => "Session done. Time for a short break",
                (FocusPhase.LONG_BREAK, true, _) => "Nice run. Time for a long break",
                (_, _, false) => "Paused",
                (FocusPhase.FOCUS, _, _) when r.Bonus => "A few more minutes",
                (FocusPhase.FOCUS, _, _) when r.Config.BreaksEnabled => $"Session {nth} of {every}",
                (FocusPhase.FOCUS, _, _) => "Focusing",
                (FocusPhase.SHORT_BREAK, _, _) => "Short break. Look away from the screen",
                _ => "Long break. Stretch, get some water",
            };
            primary = r.AwaitingStart ? (r.IsBreak ? "Start break" : "Start focus") : r.Running ? "Pause" : "Resume";
            SkipLabel.Text = r.IsBreak ? "Skip break" : r.Config.BreaksEnabled ? "Skip to break" : "Finish now";
            // The sun climbs through a focus session; a break is full daylight.
            Sky.Progress = r.IsBreak ? 1 : r.AwaitingStart ? 0 : r.Fraction(now);
        }
        Motion.SwapText(PrimaryLabel, primary);
        AutomationProperties.SetName(PrimaryButton, primary);
        AutomationProperties.SetName(Countdown, $"{TimeText.Spoken(TimeSpanMs(Countdown.Text))} {(idle ? "session" : "left")}");
        Sky.Date = EpochDay.Today();

        RenderQuiet(run);
    }

    private static long TimeSpanMs(string clock)
    {
        var parts = clock.Split(':').Select(long.Parse).ToArray();
        return parts.Length == 3 ? ((parts[0] * 60 + parts[1]) * 60 + parts[2]) * 1000 : (parts[0] * 60 + parts[1]) * 1000;
    }

    /// <summary>Sessions until the long break, as dots.</summary>
    private void RenderDots(FocusRun? run, Core.Data.FocusPreferences prefs)
    {
        var every = Math.Clamp(run?.Config.LongBreakEvery ?? prefs.LongBreakEvery, 1, 8);
        var show = run?.Config.BreaksEnabled ?? prefs.BreaksEnabled;
        CycleDots.Visibility = show ? Visibility.Visible : Visibility.Collapsed;
        var count = run?.CompletedFocusCount ?? 0;
        var filled = count > 0 && count % every == 0 && run?.Phase == FocusPhase.LONG_BREAK ? every : count % every;
        if (CycleDots.Children.Count != every)
        {
            CycleDots.Children.Clear();
            for (var i = 0; i < every; i++) CycleDots.Children.Add(new Ellipse { Width = 7, Height = 7 });
        }
        for (var i = 0; i < every; i++)
        {
            var dot = (Ellipse)CycleDots.Children[i];
            var on = i < filled;
            var wasOn = dot.Tag is true;
            dot.Fill = new SolidColorBrush(on ? Colors.White : Color.FromArgb(80, 255, 255, 255));
            dot.Tag = on;
            if (on && !wasOn && run != null) Motion.Pop(dot);
        }
        AutomationProperties.SetName(CycleDots, $"{filled} of {every} sessions before the long break");
    }

    private void RenderQuiet(FocusRun? run)
    {
        var active = QuietHours.SystemFocusActive;
        QuietIcon.Glyph = active ? "" : "";
        QuietText.Text = active ? "Do not disturb is on" : "Do not disturb";
        QuietButton.Opacity = active || run is { Phase: FocusPhase.FOCUS, Running: true } ? 1 : 0.7;
        ToolTipService.SetToolTip(QuietButton, active
            ? "Windows Focus is silencing notifications"
            : "Open Windows Focus to silence other apps. RepDayrise already holds its own reminders while you focus.");
        AutomationProperties.SetName(QuietButton, active ? "Do not disturb is on" : "Turn on Do not disturb in Windows settings");
    }

    private void FitTimer()
    {
        // Big enough to read across the room, small enough to leave the sky room to breathe.
        var size = Math.Clamp(Math.Min(ActualWidth * 0.15, (ActualHeight - 360) * 0.6), 72, 156);
        Countdown.FontSize = size;
        Countdown.LineHeight = size;
    }
}
