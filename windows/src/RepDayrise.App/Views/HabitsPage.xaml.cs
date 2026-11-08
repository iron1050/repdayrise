using System.Collections.ObjectModel;
using System.Globalization;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Automation;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Navigation;
using Microsoft.UI.Xaml.Shapes;
using RepDayrise.Core.Domain;
using RepDayrise.Core.Models;
using RepDayrise.Services;
using RepDayrise.ViewModels;

namespace RepDayrise.Views;

/// <summary>The day's habits, grouped, with the week above.</summary>
public sealed partial class HabitsPage : Page, IHabitRowHost
{
    private readonly ObservableCollection<HabitSectionViewModel> _sections = [];
    private readonly Dictionary<long, HabitRowViewModel> _rows = [];
    private readonly DispatcherQueueTimer _ticker;
    private DateOnly _selected = EpochDay.Today();
    private DateOnly _today = EpochDay.Today();

    public HabitsPage()
    {
        InitializeComponent();
        Sections.ItemsSource = _sections;
        _ticker = DispatcherQueue.GetForCurrentThread().CreateTimer();
        _ticker.Interval = TimeSpan.FromSeconds(1);
        _ticker.Tick += (_, _) => OnTick();
        Motion.FadeOnVisibility(BackToTodayButton, rise: 0);
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        AppServices.DataChanged += Refresh;
        AppServices.SettingsChanged += OnSettingsChanged;
        if (_today != EpochDay.Today()) _selected = _today = EpochDay.Today(); // a new day since we were last shown
        Refresh();
    }

    protected override void OnNavigatedFrom(NavigationEventArgs e)
    {
        AppServices.DataChanged -= Refresh;
        AppServices.SettingsChanged -= OnSettingsChanged;
        _ticker.Stop();
    }

    private void OnSettingsChanged(Core.Data.Settings _) => Refresh();

    private void Refresh()
    {
        var store = AppServices.Store;
        var logic = AppServices.Logic;
        var habits = store.GetAllHabits().Where(h => !h.Archived).ToList();
        var entries = store.GetEntries();
        var timers = store.GetTimers().ToDictionary(t => t.HabitId);
        var groups = store.GetGroups();
        var completedToBottom = AppServices.Settings.Current.CompletedToBottom;
        var culture = CultureInfo.CurrentCulture;

        // Large title
        var isToday = _selected == _today;
        TitleText.Text = isToday ? "Today" : _selected == _today.AddDays(-1) ? "Yesterday" : _selected.ToString("dddd", culture);
        DateText.Text = _selected.ToString(isToday ? "dddd, d MMMM" : "d MMMM yyyy", culture);
        BackToTodayButton.Visibility = isToday ? Visibility.Collapsed : Visibility.Visible;

        var (done, due) = logic.DayCounts(habits, entries, _selected);
        DayBar.Value = logic.DayProgress(habits, entries, _selected);
        ProgressText.Text = due == 0 ? "Nothing due" : done >= due ? "All done" : $"{done} of {due} done";
        DayProgress.Visibility = habits.Count == 0 ? Visibility.Collapsed : Visibility.Visible;

        BuildWeekStrip(habits, entries, logic, culture);

        // Sections: ungrouped habits first, then each group in order.
        var visible = habits.Where(h => logic.IsDue(h, entries, _selected)).ToList();
        var groupIds = groups.Select(g => g.Id).ToHashSet();
        var layout = new List<(long? GroupId, string Title, bool Collapsed, List<Habit> Habits)>
        {
            (null, "", false, visible.Where(h => h.GroupId == null || !groupIds.Contains(h.GroupId.Value)).ToList()),
        };
        layout.AddRange(groups.Select(g => ((long?)g.Id, g.Name, g.Collapsed, visible.Where(h => h.GroupId == g.Id).ToList())));
        layout.RemoveAll(s => s.Habits.Count == 0);

        if (!_sections.Select(s => s.GroupId).SequenceEqual(layout.Select(s => s.GroupId)))
        {
            _sections.Clear();
            foreach (var s in layout) _sections.Add(new HabitSectionViewModel { GroupId = s.GroupId });
        }

        var seen = new HashSet<long>();
        for (var i = 0; i < layout.Count; i++)
        {
            var (_, title, collapsed, list) = layout[i];
            var section = _sections[i];
            section.Title = title;
            section.IsCollapsed = collapsed;
            var ordered = completedToBottom ? list.OrderBy(h => logic.IsCompleted(h, entries, _selected)).ToList() : list;
            section.Summary = $"{list.Count(h => logic.IsCompleted(h, entries, _selected))} of {list.Count}";

            var rows = ordered.Select((h, index) =>
            {
                seen.Add(h.Id);
                if (!_rows.TryGetValue(h.Id, out var row)) _rows[h.Id] = row = new HabitRowViewModel(this);
                row.Update(h, _selected, entries, timers.GetValueOrDefault(h.Id), logic);
                row.ShowDivider = index < ordered.Count - 1;
                return row;
            }).ToList();
            if (!section.Rows.SequenceEqual(rows))
            {
                section.Rows.Clear();
                foreach (var r in rows) section.Rows.Add(r);
            }
        }
        foreach (var gone in _rows.Keys.Where(id => !seen.Contains(id)).ToList()) _rows.Remove(gone);

        // Empty state
        EmptyState.Visibility = layout.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        if (habits.Count == 0)
        {
            EmptyTitle.Text = "No habits yet";
            EmptyBody.Text = "Add something you want to do regularly. Each one you finish raises the sun a little.";
            EmptyAddButton.Visibility = Visibility.Visible;
        }
        else
        {
            EmptyTitle.Text = isToday ? "Nothing due today" : "Nothing was due";
            EmptyBody.Text = "Weekly and monthly habits leave the list once their goal for the period is met.";
            EmptyAddButton.Visibility = Visibility.Collapsed;
        }

        if (timers.Values.Any(t => t.Running)) _ticker.Start(); else _ticker.Stop();
    }

    /// <summary>The week as a calendar row: weekday, date in a circle (filled when selected), a dot when the day was done.</summary>
    private void BuildWeekStrip(IReadOnlyList<Habit> habits, EntryMap entries, HabitLogic logic, CultureInfo culture)
    {
        WeekStrip.Children.Clear();
        WeekStrip.ColumnDefinitions.Clear();
        var accent = (Brush)Application.Current.Resources["SunriseBrush"];
        var muted = (Brush)Resources.ThemeLookup("TextMutedBrush");
        var ink = (Brush)Resources.ThemeLookup("TextPrimaryBrush");

        AddArrow("", "Previous week", -7, 0);
        var days = logic.WeekDays(_selected);
        for (var i = 0; i < days.Count; i++)
        {
            var date = days[i];
            var future = date > _today;
            var selected = date == _selected;
            var isToday = date == _today;
            var fraction = future ? 0 : logic.DayProgress(habits, entries, date);

            var circle = new Grid { Width = 34, Height = 34, HorizontalAlignment = HorizontalAlignment.Center };
            circle.Children.Add(new Ellipse { Fill = selected ? accent : new SolidColorBrush(Microsoft.UI.Colors.Transparent) });
            circle.Children.Add(new TextBlock
            {
                Text = date.Day.ToString(culture),
                FontSize = 15,
                FontWeight = selected || isToday ? Microsoft.UI.Text.FontWeights.SemiBold : Microsoft.UI.Text.FontWeights.Normal,
                Foreground = selected ? new SolidColorBrush(Microsoft.UI.Colors.White) : isToday ? accent : ink,
                HorizontalAlignment = HorizontalAlignment.Center,
                VerticalAlignment = VerticalAlignment.Center,
            });
            var content = new StackPanel { Spacing = 4 };
            content.Children.Add(new TextBlock
            {
                Text = culture.DateTimeFormat.GetShortestDayName(date.DayOfWeek),
                FontSize = 12,
                Foreground = muted,
                HorizontalAlignment = HorizontalAlignment.Center,
            });
            content.Children.Add(circle);
            content.Children.Add(new Ellipse
            {
                Width = 5, Height = 5, HorizontalAlignment = HorizontalAlignment.Center,
                Fill = fraction >= 0.999f ? accent : fraction > 0 ? muted : new SolidColorBrush(Microsoft.UI.Colors.Transparent),
            });

            var button = new Button
            {
                Content = content,
                Style = (Style)Application.Current.Resources["SoftButtonStyle"],
                HorizontalAlignment = HorizontalAlignment.Stretch,
                Padding = new Thickness(0, 8, 0, 6),
                CornerRadius = new CornerRadius(10),
                IsEnabled = !future,
            };
            AutomationProperties.SetName(button, $"{date.ToString("dddd d MMMM", culture)}, {Math.Round(fraction * 100)}% done");
            button.Click += (_, _) => { _selected = date; Refresh(); };
            WeekStrip.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            Grid.SetColumn(button, WeekStrip.ColumnDefinitions.Count - 1);
            WeekStrip.Children.Add(button);
        }
        AddArrow("", "Next week", 7, WeekStrip.ColumnDefinitions.Count);
    }

    private void AddArrow(string glyph, string name, int days, int column)
    {
        var target = _selected.AddDays(days);
        if (target > _today) target = _today;
        var button = new Button
        {
            Content = new FontIcon { Glyph = glyph, FontSize = 11 },
            Style = (Style)Application.Current.Resources["ChromeButtonStyle"],
            Width = 28,
            Height = 60,
            IsEnabled = AppServices.Logic.WeekStartOf(target) != AppServices.Logic.WeekStartOf(_selected),
        };
        AutomationProperties.SetName(button, name);
        ToolTipService.SetToolTip(button, name);
        button.Click += (_, _) => { _selected = target; Refresh(); };
        WeekStrip.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        Grid.SetColumn(button, column);
        WeekStrip.Children.Add(button);
    }

    private void OnTick()
    {
        foreach (var row in _rows.Values.Where(r => r.TimerRunning)) row.Tick();
        if (_today != EpochDay.Today()) { _selected = _today = EpochDay.Today(); Refresh(); }
    }

    private void OnBackToToday(object sender, RoutedEventArgs e)
    {
        _selected = _today;
        Refresh();
    }

    private void OnNewHabit(object sender, RoutedEventArgs e) => Frame.Navigate(typeof(EditHabitPage), 0L);

    /// <summary>Check-off with a little pop when it completes.</summary>
    private void OnCheck(object sender, RoutedEventArgs e)
    {
        if (sender is not Button { Tag: HabitRowViewModel row } button) return;
        var completing = !row.IsDone;
        row.ToggleCheck();
        if (completing) Motion.Pop(button);
    }

    private void OnIncrement(object sender, RoutedEventArgs e)
    {
        if (sender is not Button { Tag: HabitRowViewModel row } button) return;
        var before = row.IsDone;
        row.Increment();
        if (!before && row.Percent >= 100 - 100.0 / Math.Max(row.Habit.EffectiveGoal, 1)) Motion.Pop((UIElement)button.Parent);
    }

    // ---- IHabitRowHost ----------------------------------------------------------------------

    public void StartFocus(HabitRowViewModel row)
    {
        var focus = AppServices.Focus;
        AppServices.Settings.Update(s => s with { Focus = s.Focus with { LastHabitUid = row.Habit.Uid } });
        if (focus.Current == null) focus.Start(AppServices.Settings.Current.Focus.ToConfig(), row.Id);
        else focus.SetHabit(row.Id);
        App.Current?.Window?.Go(Destination.Focus);
    }

    public void OpenHabit(HabitRowViewModel row) => Frame.Navigate(typeof(HabitDetailPage), row.Id);

    public void EditHabit(HabitRowViewModel row) => Frame.Navigate(typeof(EditHabitPage), row.Id);

    public async Task SetValue(HabitRowViewModel row)
    {
        var value = await Dialogs.SetValue(XamlRoot, row.Habit, row.Date, AppServices.Store.CurrentValue(row.Id, row.Date));
        if (value is { } v) AppServices.Store.SetValue(row.Id, row.Date, v);
    }

    public Task ArchiveHabit(HabitRowViewModel row)
    {
        AppServices.Store.SetArchived(row.Id, true);
        return Task.CompletedTask;
    }

    public async Task DeleteHabit(HabitRowViewModel row)
    {
        if (await Dialogs.Confirm(XamlRoot, $"Delete {row.Name}?", "Its history will be deleted too. Archive it instead if you might want it back.", "Delete"))
            AppServices.Store.DeleteHabit(row.Id);
    }
}

internal static class ThemeLookupExtensions
{
    /// <summary>Finds a theme resource for the element's current theme (Application.Resources ignores RequestedTheme).</summary>
    public static object ThemeLookup(this ResourceDictionary _, string key)
    {
        var theme = App.Current?.Window?.Content is FrameworkElement root && root.ActualTheme == ElementTheme.Light ? "Light" : "Dark";
        var dict = Application.Current.Resources.MergedDictionaries
            .Select(d => d.ThemeDictionaries.TryGetValue(theme, out var t) ? t as ResourceDictionary : null)
            .FirstOrDefault(d => d != null && d.ContainsKey(key));
        return dict?[key] ?? Application.Current.Resources[key];
    }
}
