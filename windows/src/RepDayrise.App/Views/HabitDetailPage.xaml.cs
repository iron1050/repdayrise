using System.Globalization;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;
using RepDayrise.Core.Models;
using RepDayrise.Services;

namespace RepDayrise.Views;

/// <summary>One habit: streaks, a month calendar to edit past days, and the year graph.</summary>
public sealed partial class HabitDetailPage : Page
{
    private long _id;
    private Habit? _habit;

    public HabitDetailPage()
    {
        InitializeComponent();
        Calendar.DayInvoked += OnDayInvoked;
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        _id = e.Parameter is long id ? id : 0;
        AppServices.DataChanged += Refresh;
        Refresh();
    }

    protected override void OnNavigatedFrom(NavigationEventArgs e) => AppServices.DataChanged -= Refresh;

    private void Refresh()
    {
        var store = AppServices.Store;
        _habit = store.GetHabit(_id);
        if (_habit == null)
        {
            // Deleted elsewhere (or a stale reminder link).
            if (Frame.CanGoBack) Frame.GoBack();
            return;
        }
        var h = _habit;
        var entries = store.GetEntries();
        var logic = AppServices.Logic;
        var today = EpochDay.Today();
        var culture = CultureInfo.CurrentCulture;

        BadgeCircle.Fill = HabitVisuals.BrushOf(h.ColorIndex);
        BadgeIcon.Glyph = HabitVisuals.Glyph(h.Icon);
        NameText.Text = h.Name;
        SubtitleText.Text = $"{h.GoalLabel()} · {h.ScheduleLabel()} · since {h.StartDate.ToString("MMM d, yyyy", culture)}";
        ArchivedBar.IsOpen = h.Archived;
        ArchiveButton.Label = h.Archived ? "Restore" : "Archive";

        var stats = logic.Stats(h, entries, today);
        var unit = stats.StreakUnit;
        CurrentStreakText.Text = stats.CurrentStreak.ToString(culture);
        CurrentStreakLabel.Text = $"Current streak ({unit})";
        BestStreakText.Text = stats.BestStreak.ToString(culture);
        BestStreakLabel.Text = $"Best streak ({unit})";
        TotalText.Text = stats.TotalDays.ToString(culture);
        MonthText.Text = $"{Math.Round(logic.MonthCompletion(h, entries, today, today) * 100)}%";
        MonthLabel.Text = $"{today.ToString("MMMM", culture)} so far";

        Calendar.Show(h, entries, logic);
        CalendarHint.Text = h.Type == HabitType.CHECK
            ? "Click a day to mark it done or not done."
            : "Click a day to set how much you did.";

        var start = logic.GraphStart(today);
        YearGraph.Show(logic.HabitHeatmap(h, entries, start, today), start, today, HabitVisuals.ColorOf(h.ColorIndex), logic.WeekStart);

        NotePanel.Visibility = string.IsNullOrWhiteSpace(h.Note) ? Visibility.Collapsed : Visibility.Visible;
        NoteText.Text = h.Note;
    }

    private async void OnDayInvoked(DateOnly date)
    {
        if (_habit == null) return;
        if (_habit.Type == HabitType.CHECK)
        {
            AppServices.Store.Toggle(_habit, date);
            return;
        }
        var value = await Dialogs.SetValue(XamlRoot, _habit, date, AppServices.Store.CurrentValue(_habit.Id, date));
        if (value is { } v) AppServices.Store.SetValue(_habit.Id, date, v);
    }

    private void OnEdit(object sender, RoutedEventArgs e) => Frame.Navigate(typeof(EditHabitPage), _id);

    private void OnArchive(object sender, RoutedEventArgs e)
    {
        if (_habit != null) AppServices.Store.SetArchived(_id, !_habit.Archived);
    }

    private async void OnDelete(object sender, RoutedEventArgs e)
    {
        if (_habit == null) return;
        if (await Dialogs.Confirm(XamlRoot, $"Delete {_habit.Name}?", "Its history will be deleted too. Archive it instead if you might want it back.", "Delete"))
            AppServices.Store.DeleteHabit(_id); // Refresh() then navigates back
    }
}
