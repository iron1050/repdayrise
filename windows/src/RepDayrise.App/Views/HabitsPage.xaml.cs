using System.Collections.ObjectModel;
using System.Globalization;
using Microsoft.UI.Dispatching;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Navigation;
using RepDayrise.Core.Domain;
using RepDayrise.Core.Models;
using RepDayrise.Services;
using RepDayrise.ViewModels;

namespace RepDayrise.Views;

/// <summary>Today's habits, grouped, with the week above. A port of the Android list view.</summary>
public sealed partial class HabitsPage : Page, IHabitRowHost, ISkyPage
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

        // Header: tinted from indigo to green as the day fills (the Android list header)
        var isToday = _selected == _today;
        DayRelative.Text = isToday ? "Today" : _selected == _today.AddDays(-1) ? "Yesterday" : _selected.ToString("dddd", culture);
        DayDate.Text = ", " + _selected.ToString("d MMM", culture);
        BackToTodayButton.Visibility = isToday ? Visibility.Collapsed : Visibility.Visible;
        var (done, due) = logic.DayCounts(habits, entries, _selected);
        var progress = logic.DayProgress(habits, entries, _selected);
        ProgressText.Text = due == 0 ? "A quiet day" : done == due ? "The sun is up" : $"{done} of {due} done";
        ProgressDetail.Text = due == 0
            ? MoonPhase.Name(_selected)
            : $"{Math.Round(progress * 100)}% of the way to sunrise · {MoonPhase.Name(_selected)}";
        HeaderTint.Background = HeaderBrush(progress);

        BuildWeekStrip(habits, entries, logic);

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
            section.Summary = $"{list.Count(h => logic.IsCompleted(h, entries, _selected))}/{list.Count}";

            var rows = ordered.Select(h =>
            {
                seen.Add(h.Id);
                if (!_rows.TryGetValue(h.Id, out var row)) _rows[h.Id] = row = new HabitRowViewModel(this);
                row.Update(h, _selected, entries, timers.GetValueOrDefault(h.Id), logic);
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
            EmptyTitle.Text = "Start your first habit";
            EmptyBody.Text = "Add something you want to do regularly. Each habit you finish raises the sun a little higher.";
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

    private void BuildWeekStrip(IReadOnlyList<Habit> habits, EntryMap entries, HabitLogic logic)
    {
        WeekStrip.Children.Clear();
        WeekStrip.ColumnDefinitions.Clear();
        var culture = CultureInfo.CurrentCulture;
        var days = logic.WeekDays(_selected);
        for (var i = 0; i < days.Count; i++)
        {
            var date = days[i];
            WeekStrip.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
            var future = date > _today;
            var selected = date == _selected;

            // A day chip on the tinted header: name, number and a thin bar of how much got done.
            var ink = new SolidColorBrush(selected ? Windows.UI.Color.FromArgb(255, 0x1A, 0x1B, 0x2E) : Microsoft.UI.Colors.White);
            var content = new StackPanel { Spacing = 3 };
            content.Children.Add(new TextBlock
            {
                Text = culture.DateTimeFormat.GetAbbreviatedDayName(date.DayOfWeek),
                FontSize = 12, HorizontalAlignment = HorizontalAlignment.Center, Foreground = ink, Opacity = 0.75,
            });
            content.Children.Add(new TextBlock
            {
                Text = date.Day.ToString(culture), FontSize = 18, HorizontalAlignment = HorizontalAlignment.Center, Foreground = ink,
                FontWeight = Microsoft.UI.Text.FontWeights.Bold,
            });
            var fraction = future ? 0 : logic.DayProgress(habits, entries, date);
            var track = new Grid { Height = 4, Width = 28, CornerRadius = new CornerRadius(2), Margin = new Thickness(0, 3, 0, 0),
                Background = new SolidColorBrush(selected ? Windows.UI.Color.FromArgb(40, 0, 0, 0) : Windows.UI.Color.FromArgb(56, 255, 255, 255)) };
            track.Children.Add(new Border
            {
                Width = 28 * fraction, HorizontalAlignment = HorizontalAlignment.Left, CornerRadius = new CornerRadius(2),
                Background = new SolidColorBrush(selected ? HabitVisuals.Sunrise : Microsoft.UI.Colors.White),
            });
            content.Children.Add(track);

            var button = new Button
            {
                Content = content, HorizontalAlignment = HorizontalAlignment.Stretch, Height = 72,
                Style = (Style)Application.Current.Resources["SoftButtonStyle"],
                CornerRadius = new CornerRadius(16),
                Background = new SolidColorBrush(selected ? Microsoft.UI.Colors.White : Windows.UI.Color.FromArgb(31, 255, 255, 255)),
                IsEnabled = !future,
            };
            Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(button, date.ToString("D", culture));
            button.Click += (_, _) => { _selected = date; Refresh(); };
            Grid.SetColumn(button, i);
            WeekStrip.Children.Add(button);
        }

        // Arrows to step a week back and forward.
        AddWeekArrow("", "Previous week", -7, insertFirst: true);
        AddWeekArrow("", "Next week", 7, insertFirst: false);
    }

    private void AddWeekArrow(string glyph, string name, int days, bool insertFirst)
    {
        var target = _selected.AddDays(days);
        if (target > _today) target = _today;
        var button = new Button
        {
            Content = new FontIcon { Glyph = glyph, FontSize = 12 },
            Style = (Style)Application.Current.Resources["SoftButtonStyle"],
            Foreground = new SolidColorBrush(Microsoft.UI.Colors.White),
            CornerRadius = new CornerRadius(12),
            VerticalAlignment = VerticalAlignment.Stretch,
            Padding = new Thickness(8, 0, 8, 0),
            IsEnabled = target != _selected && AppServices.Logic.WeekStartOf(target) != AppServices.Logic.WeekStartOf(_selected),
        };
        Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(button, name);
        ToolTipService.SetToolTip(button, name);
        button.Click += (_, _) => { _selected = target; Refresh(); };
        if (insertFirst)
        {
            WeekStrip.ColumnDefinitions.Insert(0, new ColumnDefinition { Width = GridLength.Auto });
            foreach (var child in WeekStrip.Children.OfType<FrameworkElement>()) Grid.SetColumn(child, Grid.GetColumn(child) + 1);
            Grid.SetColumn(button, 0);
        }
        else
        {
            WeekStrip.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
            Grid.SetColumn(button, WeekStrip.ColumnDefinitions.Count - 1);
        }
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

    /// <summary>Indigo at the start of the day to green when it's done, fading into the page.</summary>
    private static LinearGradientBrush HeaderBrush(float progress)
    {
        Windows.UI.Color Lerp(Windows.UI.Color a, Windows.UI.Color b, float t) => Windows.UI.Color.FromArgb(255,
            (byte)(a.R + (b.R - a.R) * t), (byte)(a.G + (b.G - a.G) * t), (byte)(a.B + (b.B - a.B) * t));
        var tint = Lerp(Windows.UI.Color.FromArgb(255, 0x2E, 0x34, 0x82), Windows.UI.Color.FromArgb(255, 0x1F, 0x7A, 0x4D), progress);
        return new LinearGradientBrush
        {
            StartPoint = new Windows.Foundation.Point(0.5, 0),
            EndPoint = new Windows.Foundation.Point(0.5, 1),
            GradientStops =
            {
                new GradientStop { Color = tint, Offset = 0 },
                new GradientStop { Color = tint, Offset = 0.55 },
                new GradientStop { Color = Windows.UI.Color.FromArgb(0, tint.R, tint.G, tint.B), Offset = 1 },
            },
        };
    }

    // ---- IHabitRowHost ----------------------------------------------------------------------

    public void StartFocus(HabitRowViewModel row)
    {
        var focus = AppServices.Focus;
        AppServices.Settings.Update(s => s with { Focus = s.Focus with { LastHabitUid = row.Habit.Uid } });
        if (focus.Current == null) focus.Start(AppServices.Settings.Current.Focus.ToConfig(), row.Id);
        else focus.SetHabit(row.Id);
        App.Current?.Window?.Go(Controls.Destination.Focus);
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
