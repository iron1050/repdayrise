using System.Globalization;
using Microsoft.UI;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Navigation;
using RepDayrise.Core.Domain;
using RepDayrise.Core.Models;
using RepDayrise.Services;
using Windows.UI;

namespace RepDayrise.Views;

public sealed record HabitSummary(long Id, string Name, string Glyph, SolidColorBrush Brush, string Detail, double MonthPercent, string MonthText);

/// <summary>The day's sky and the numbers behind it: the week, the month, focus and the year.</summary>
public sealed partial class ProgressPage : Page, ISkyPage
{
    private static readonly Color TileLow = Color.FromArgb(255, 0x9C, 0xC4, 0xF0);
    private static readonly Color TileHigh = Color.FromArgb(255, 0x2F, 0x6B, 0xC9);

    private DateOnly _selected = EpochDay.Today();

    public ProgressPage()
    {
        InitializeComponent();
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        _selected = EpochDay.Today();
        AppServices.DataChanged += Refresh;
        AppServices.SettingsChanged += OnSettings;
        Refresh();
    }

    protected override void OnNavigatedFrom(NavigationEventArgs e)
    {
        AppServices.DataChanged -= Refresh;
        AppServices.SettingsChanged -= OnSettings;
    }

    private void OnSettings(Core.Data.Settings _) => Refresh();

    private void Refresh()
    {
        var store = AppServices.Store;
        var logic = AppServices.Logic;
        var all = store.GetAllHabits();
        var active = all.Where(h => !h.Archived).ToList();
        var entries = store.GetEntries();
        var today = EpochDay.Today();
        var culture = CultureInfo.CurrentCulture;

        // ---- Hero: the selected day ----
        var (done, due) = logic.DayCounts(active, entries, _selected);
        var progress = logic.DayProgress(active, entries, _selected);
        Sky.Progress = progress;
        Sky.Date = _selected;
        DayRelative.Text = Relative(_selected, today, culture);
        DayDate.Text = ", " + _selected.ToString("d MMM", culture);
        SkyStatus.Text = due == 0 ? "Rest day" : done >= due ? "Completed" : $"{due - done} to go";
        DoneCount.Text = done.ToString(culture);
        LeftCount.Text = Math.Max(due - done, 0).ToString(culture);
        Percent.Text = $"{Math.Round(progress * 100)}%";

        // ---- The week ----
        DayTiles.Children.Clear();
        DayTiles.ColumnDefinitions.Clear();
        var days = logic.WeekDays(_selected);
        for (var i = 0; i < days.Count; i++)
        {
            DayTiles.ColumnDefinitions.Add(new ColumnDefinition());
            var tile = DayTile(days[i], logic.DayProgress(active, entries, days[i]), days[i] == _selected, days[i] > today, culture);
            Grid.SetColumn(tile, i);
            DayTiles.Children.Add(tile);
        }

        // ---- Dashboard tiles ----
        var monthByHabit = active.Select(h => (Habit: h, Month: logic.MonthCompletion(h, entries, today, today))).ToList();
        var month = monthByHabit.Count == 0 ? 0 : monthByHabit.Average(x => x.Month);
        MonthPercent.Text = $"{Math.Round(month * 100)}%";
        MonthBar.Value = month * 100;
        var top = monthByHabit.OrderByDescending(x => x.Month).FirstOrDefault();
        TopHabit.Text = top.Habit?.Name ?? "—";
        TopHabitDetail.Text = top.Habit != null ? $"{Math.Round(top.Month * 100)}% this month" : "Add a habit to start";
        var best = active.Select(h => (Habit: h, Stats: logic.Stats(h, entries, today))).OrderByDescending(x => x.Stats.BestStreak).FirstOrDefault();
        BestStreak.Text = (best.Stats?.BestStreak ?? 0).ToString(culture);
        BestStreakDetail.Text = best.Habit != null ? $"{best.Stats!.StreakUnit} · {best.Habit.Name}" : "";
        var start = logic.GraphStart(today);
        var overall = logic.OverallHeatmap(active, entries, start, today);
        var yearStart = new DateOnly(today.Year, 1, 1).ToEpochDay();
        ActiveDays.Text = overall.Count(kv => kv.Key >= yearStart).ToString(culture);
        ActiveDaysDetail.Text = $"in {today.Year} · {overall.Count(kv => kv.Key >= yearStart && kv.Value >= 0.999f)} sunrises";
        YearGraph.Show(overall, start, today, HabitVisuals.Sunrise, logic.WeekStart);

        RefreshFocus(today, logic, all, culture);

        // ---- Habits ----
        HabitList.ItemsSource = active.Select(h =>
        {
            var stats = logic.Stats(h, entries, today);
            var m = logic.MonthCompletion(h, entries, today, today);
            return new HabitSummary(h.Id, h.Name, HabitVisuals.Glyph(h.Icon), HabitVisuals.BrushOf(h.ColorIndex),
                $"Streak {stats.CurrentStreak} {stats.StreakUnit} · best {stats.BestStreak} · done {stats.TotalDays}×",
                m * 100, $"{Math.Round(m * 100)}% in {today.ToString("MMMM", culture)}");
        }).ToList();
    }

    private void RefreshFocus(DateOnly today, HabitLogic logic, IReadOnlyList<Habit> habits, CultureInfo culture)
    {
        var goal = AppServices.Settings.Current.Focus.DailyGoalMinutes;
        var start = logic.GraphStart(today);
        var sessions = AppServices.Store.GetFocusSessions(start.AddDays(-7), today);
        var all = AppServices.Store.GetAllFocusSessions();
        var sum = FocusStats.Summary(all, today, logic, goal);

        FocusToday.Text = Format.Duration(sum.TodayMinutes);
        FocusTodaySessions.Text = sum.TodaySessions == 1 ? "1 session" : $"{sum.TodaySessions} sessions";
        FocusWeek.Text = Format.Duration(sum.WeekMinutes);
        FocusStreak.Text = sum.CurrentStreak == 1 ? "1 day" : $"{sum.CurrentStreak} days";
        FocusBest.Text = $"best {sum.BestStreak}";
        FocusTotal.Text = Format.Duration(sum.TotalMinutes);
        FocusTotalSessions.Text = sum.TotalSessions == 1 ? "1 session" : $"{sum.TotalSessions} sessions";
        FocusGoalText.Text = $"Daily goal {Format.Duration(goal)}";

        const int days = 14;
        var minutes = FocusStats.LastDays(sessions, today, days);
        var dates = Enumerable.Range(0, days).Select(i => today.AddDays(i - days + 1)).ToList();
        FocusBars.Show(minutes,
            dates.Select(d => d.ToString("ddd", culture)[..2]).ToList(),
            dates.Select((d, i) => $"{d.ToString("ddd d MMM", culture)}: {Format.Duration(minutes[i])}").ToList(),
            HabitVisuals.Sunrise, highlight: days - 1);

        FocusGraph.Show(FocusStats.Heatmap(sessions, start, today, goal), start, today, Color.FromArgb(255, 0x7C, 0x6C, 0xF0), logic.WeekStart);

        FocusByHabit.Children.Clear();
        var byName = habits.ToDictionary(h => h.Id);
        foreach (var (habitId, mins) in FocusStats.ByHabit(all).Take(5))
        {
            var habit = habitId is long id ? byName.GetValueOrDefault(id) ?? AppServices.Store.GetHabit(id) : null;
            var row = new Grid { ColumnSpacing = 10 };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            row.ColumnDefinitions.Add(new ColumnDefinition());
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var dot = new Border
            {
                Width = 10, Height = 10, CornerRadius = new CornerRadius(5), VerticalAlignment = VerticalAlignment.Center,
                Background = habit != null ? HabitVisuals.BrushOf(habit.ColorIndex) : (Brush)Application.Current.Resources["TextMutedBrush"],
            };
            var name = new TextBlock { Text = habit?.Name ?? "Untagged", Style = (Style)Application.Current.Resources["BodyMediumStyle"] };
            var value = new TextBlock { Text = Format.Duration(mins), Style = (Style)Application.Current.Resources["TitleSmallStyle"] };
            Grid.SetColumn(name, 1);
            Grid.SetColumn(value, 2);
            row.Children.Add(dot);
            row.Children.Add(name);
            row.Children.Add(value);
            FocusByHabit.Children.Add(row);
        }
    }

    /// <summary>A day of the week, shaded by how much of it got done (DayTileStrip on Android).</summary>
    private Button DayTile(DateOnly day, float progress, bool selected, bool future, CultureInfo culture)
    {
        var fill = Lerp(TileLow, TileHigh, progress);
        var stack = new StackPanel { Spacing = 2, HorizontalAlignment = HorizontalAlignment.Center };
        stack.Children.Add(new TextBlock
        {
            Text = day.ToString("ddd", culture), FontSize = 12, HorizontalAlignment = HorizontalAlignment.Center,
            Foreground = new SolidColorBrush(Color.FromArgb(200, 255, 255, 255)),
        });
        stack.Children.Add(new TextBlock
        {
            Text = day.Day.ToString(culture), FontSize = 18, FontWeight = Microsoft.UI.Text.FontWeights.Bold,
            HorizontalAlignment = HorizontalAlignment.Center, Foreground = new SolidColorBrush(Colors.White),
        });
        var button = new Button
        {
            Style = (Style)Application.Current.Resources["SoftButtonStyle"],
            Height = 62,
            HorizontalAlignment = HorizontalAlignment.Stretch,
            CornerRadius = new CornerRadius(16),
            Background = new SolidColorBrush(future ? Color.FromArgb(90, fill.R, fill.G, fill.B) : fill),
            BorderBrush = new SolidColorBrush(selected ? Colors.White : Colors.Transparent),
            BorderThickness = new Thickness(2),
            Content = stack,
            Tag = day,
        };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(button, $"{day.ToString("dddd d MMMM", culture)}, {Math.Round(progress * 100)}%");
        button.Click += (_, _) => { _selected = day; Refresh(); };
        return button;
    }

    private static string Relative(DateOnly date, DateOnly today, CultureInfo culture) =>
        date == today ? "Today" : date == today.AddDays(-1) ? "Yesterday" : date == today.AddDays(1) ? "Tomorrow" : date.ToString("dddd", culture);

    private static Color Lerp(Color a, Color b, float t) => Color.FromArgb(255,
        (byte)(a.R + (b.R - a.R) * t), (byte)(a.G + (b.G - a.G) * t), (byte)(a.B + (b.B - a.B) * t));

    private void OnSessionHistory(object sender, RoutedEventArgs e) => Frame.Navigate(typeof(FocusHistoryPage));

    private void OnOpenHabit(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { Tag: long id }) Frame.Navigate(typeof(HabitDetailPage), id);
    }
}
