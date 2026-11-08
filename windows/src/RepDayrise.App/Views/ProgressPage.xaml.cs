using System.Globalization;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Navigation;
using RepDayrise.Core.Domain;
using RepDayrise.Core.Models;
using RepDayrise.Services;
using Windows.UI;

namespace RepDayrise.Views;

public sealed record HabitSummary(long Id, string Name, string Glyph, SolidColorBrush Brush, string Detail, string MonthText, Visibility ShowDivider);

/// <summary>Today's sky and the numbers behind it: focus over two weeks and a year, and every habit's streak.</summary>
public sealed partial class ProgressPage : Page
{
    public ProgressPage()
    {
        InitializeComponent();
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
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
        var active = store.GetAllHabits().Where(h => !h.Archived).ToList();
        var entries = store.GetEntries();
        var today = EpochDay.Today();
        var culture = CultureInfo.CurrentCulture;

        DateText.Text = today.ToString("dddd, d MMMM", culture);

        // ---- Today's sky ----
        var (done, due) = logic.DayCounts(active, entries, today);
        var progress = logic.DayProgress(active, entries, today);
        Sky.Progress = progress;
        Sky.Date = today;
        SkyHeadline.Text = due == 0 ? "A quiet day" : done >= due ? "The sun is up" : $"{done} of {due} done";
        SkyDetail.Text = due == 0 ? MoonPhase.Name(today) : $"{Math.Round(progress * 100)}% of the way to sunrise";

        // ---- Habits over time ----
        var start = logic.GraphStart(today);
        var overall = logic.OverallHeatmap(active, entries, start, today);
        YearGraph.Show(overall, start, today, HabitVisuals.Sunrise, logic.WeekStart);
        var sunrises = overall.Count(kv => kv.Value >= 0.999f);
        SunriseCount.Text = sunrises == 1 ? "1 day fully done" : $"{sunrises} days fully done";
        var month = active.Count == 0 ? 0 : active.Average(h => logic.MonthCompletion(h, entries, today, today));
        MonthPercent.Text = $"{Math.Round(month * 100)}%";

        HabitList.ItemsSource = active.Select((h, i) =>
        {
            var stats = logic.Stats(h, entries, today);
            var m = logic.MonthCompletion(h, entries, today, today);
            var unit = stats.StreakUnit.TrimEnd('s');
            var detail = stats.CurrentStreak > 0
                ? $"{stats.CurrentStreak}-{unit} streak, best {stats.BestStreak}"
                : stats.BestStreak > 0 ? $"Best streak {stats.BestStreak} {stats.StreakUnit}" : "No streak yet";
            return new HabitSummary(h.Id, h.Name, HabitVisuals.Glyph(h.Icon), HabitVisuals.BrushOf(h.ColorIndex), detail,
                $"{Math.Round(m * 100)}%", i < active.Count - 1 ? Visibility.Visible : Visibility.Collapsed);
        }).ToList();
        NoHabits.Visibility = active.Count == 0 ? Visibility.Visible : Visibility.Collapsed;

        RefreshFocus(today, logic, culture);
    }

    private void RefreshFocus(DateOnly today, HabitLogic logic, CultureInfo culture)
    {
        var goal = AppServices.Settings.Current.Focus.DailyGoalMinutes;
        var start = logic.GraphStart(today);
        var all = AppServices.Store.GetAllFocusSessions();
        var sum = FocusStats.Summary(all, today, logic, goal);

        FocusToday.Text = TimeText.Short(sum.TodayMinutes);
        FocusWeek.Text = TimeText.Short(sum.WeekMinutes);
        FocusStreak.Text = sum.CurrentStreak == 1 ? "1 day" : $"{sum.CurrentStreak} days";
        FocusGoalText.Text = $"Daily goal {TimeText.Short(goal)}";

        const int days = 14;
        var minutes = FocusStats.LastDays(all, today, days);
        FocusTotal.Text = $"{TimeText.Short(minutes.Sum())} in total";
        var dates = Enumerable.Range(0, days).Select(i => today.AddDays(i - days + 1)).ToList();
        FocusBars.Show(minutes,
            dates.Select(d => culture.DateTimeFormat.GetShortestDayName(d.DayOfWeek)).ToList(),
            dates.Select((d, i) => $"{d.ToString("dddd d MMMM", culture)}: {TimeText.Short(minutes[i])}").ToList(),
            HabitVisuals.Sunrise, highlight: days - 1);

        FocusGraph.Show(FocusStats.Heatmap(all, start, today, goal), start, today, HabitVisuals.Sunrise, logic.WeekStart);

        FocusByHabit.Children.Clear();
        var habits = AppServices.Store.GetAllHabits().ToDictionary(h => h.Id);
        var total = Math.Max(all.Sum(s => s.FocusedMinutes), 1);
        foreach (var (habitId, mins) in FocusStats.ByHabit(all).Take(5))
        {
            var habit = habitId is long id ? habits.GetValueOrDefault(id) ?? AppServices.Store.GetHabit(id) : null;
            var color = habit != null ? HabitVisuals.ColorOf(habit.ColorIndex) : Color.FromArgb(255, 0x8A, 0x8C, 0x96);
            var row = new Grid { ColumnSpacing = 12 };
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(140) });
            row.ColumnDefinitions.Add(new ColumnDefinition());
            row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            var name = new TextBlock
            {
                Text = habit?.Name ?? "No habit", Style = (Style)Application.Current.Resources["BodyMediumStyle"],
                TextWrapping = TextWrapping.NoWrap, TextTrimming = TextTrimming.CharacterEllipsis, VerticalAlignment = VerticalAlignment.Center,
            };
            // A proportion bar: the share of all focus time.
            var bar = new Grid { Height = 6, CornerRadius = new CornerRadius(3), VerticalAlignment = VerticalAlignment.Center,
                Background = new SolidColorBrush(Color.FromArgb(36, color.R, color.G, color.B)) };
            var fill = new Border { CornerRadius = new CornerRadius(3), Background = new SolidColorBrush(color), HorizontalAlignment = HorizontalAlignment.Left };
            bar.SizeChanged += (_, e) => fill.Width = e.NewSize.Width * mins / total;
            bar.Children.Add(fill);
            var value = new TextBlock
            {
                Text = TimeText.Short(mins), Style = (Style)Application.Current.Resources["BodyMediumStyle"],
                Foreground = (Brush)Application.Current.Resources["TextMutedBrush"], VerticalAlignment = VerticalAlignment.Center,
            };
            Grid.SetColumn(bar, 1);
            Grid.SetColumn(value, 2);
            row.Children.Add(name);
            row.Children.Add(bar);
            row.Children.Add(value);
            FocusByHabit.Children.Add(row);
        }
    }

    private void OnSessionHistory(object sender, RoutedEventArgs e) => Frame.Navigate(typeof(FocusHistoryPage));

    private void OnOpenHabit(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { Tag: long id }) Frame.Navigate(typeof(HabitDetailPage), id);
    }
}
