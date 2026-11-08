using System.Globalization;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Navigation;
using RepDayrise.Core.Models;
using RepDayrise.Services;

namespace RepDayrise.Views;

public sealed record HabitSummary(long Id, string Name, string Glyph, SolidColorBrush Brush, string Detail, double MonthPercent, string MonthText);

public sealed partial class HistoryPage : Page
{
    public HistoryPage()
    {
        InitializeComponent();
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        AppServices.DataChanged += Refresh;
        Refresh();
    }

    protected override void OnNavigatedFrom(NavigationEventArgs e) => AppServices.DataChanged -= Refresh;

    private void Refresh()
    {
        var store = AppServices.Store;
        var logic = AppServices.Logic;
        var all = store.GetAllHabits();
        var active = all.Where(h => !h.Archived).ToList();
        var entries = store.GetEntries();
        var today = EpochDay.Today();
        var culture = CultureInfo.CurrentCulture;

        var start = logic.GraphStart(today);
        var overall = logic.OverallHeatmap(active, entries, start, today);
        YearGraph.Show(overall, start, today, HabitVisuals.Sunrise, logic.WeekStart);

        var yearStart = new DateOnly(today.Year, 1, 1).ToEpochDay();
        SunrisesText.Text = overall.Count(kv => kv.Key >= yearStart && kv.Value >= 0.999f).ToString(culture);
        ActiveDaysText.Text = overall.Count(kv => kv.Key >= yearStart).ToString(culture);
        var week = logic.WeekDays(today).Where(d => d <= today).ToList();
        var weekDone = week.Sum(d => logic.DayCounts(active, entries, d).Done);
        var weekDue = week.Sum(d => logic.DayCounts(active, entries, d).Due);
        WeekText.Text = weekDue == 0 ? "—" : $"{weekDone} of {weekDue}";

        HabitSummary Summarize(Habit h)
        {
            var stats = logic.Stats(h, entries, today);
            var month = logic.MonthCompletion(h, entries, today, today);
            var unit = stats.StreakUnit;
            var detail = h.Archived && h.ArchivedAt is { } at
                ? $"Archived {DateTimeOffset.FromUnixTimeMilliseconds(at).LocalDateTime.ToString("MMM d, yyyy", culture)} · best streak {stats.BestStreak} {unit}"
                : $"Streak {stats.CurrentStreak} {unit} · best {stats.BestStreak} · done {stats.TotalDays}×";
            return new HabitSummary(h.Id, h.Name, HabitVisuals.Glyph(h.Icon), HabitVisuals.BrushOf(h.ColorIndex), detail,
                month * 100, $"{Math.Round(month * 100)}% in {today.ToString("MMMM", culture)}");
        }

        HabitList.ItemsSource = active.Select(Summarize).ToList();
        NoHabitsText.Visibility = active.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        var archived = all.Where(h => h.Archived).OrderByDescending(h => h.ArchivedAt).Select(Summarize).ToList();
        ArchivedList.ItemsSource = archived;
        ArchivedExpander.Visibility = archived.Count == 0 ? Visibility.Collapsed : Visibility.Visible;
        ArchivedExpander.Header = $"Archived ({archived.Count})";
    }

    private void OnOpenHabit(object sender, RoutedEventArgs e)
    {
        if (sender is FrameworkElement { Tag: long id }) Frame.Navigate(typeof(HabitDetailPage), id);
    }
}
