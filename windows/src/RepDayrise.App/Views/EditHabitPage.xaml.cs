using System.Globalization;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Controls.Primitives;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Navigation;
using RepDayrise.Core.Models;
using RepDayrise.Services;

namespace RepDayrise.Views;

/// <summary>Creates or edits a habit. Navigate with the habit id, or 0 for a new one.</summary>
public sealed partial class EditHabitPage : Page
{
    private sealed record GroupItem(long? Id, string Name);

    private static readonly IReadOnlyList<ColorChoice> Colors = Enumerable.Range(0, HabitVisuals.Palette.Length).Select(i => new ColorChoice(i)).ToList();

    private Habit _habit = new() { Name = "" };
    private List<Reminder> _reminders = [];
    private int _daysMask = DaysMask.ALL;
    private bool _loading;

    public EditHabitPage()
    {
        InitializeComponent();
        ColorGrid.ItemsSource = Colors;
        IconGrid.ItemsSource = HabitVisuals.Choices;
    }

    protected override void OnNavigatedTo(NavigationEventArgs e)
    {
        var id = e.Parameter is long l ? l : 0;
        var existing = id > 0 ? AppServices.Store.GetHabit(id) : null;
        _habit = existing ?? new Habit { Name = "", ColorIndex = AppServices.Store.GetAllHabits().Count % HabitVisuals.Palette.Length };
        // A new timer habit, e.g. from the Focus page's habit picker.
        if (existing == null && e.Parameter is HabitType type)
            _habit = _habit with { Type = type, Goal = type == HabitType.TIMER ? 60 : _habit.Goal, Icon = type == HabitType.TIMER ? "timer" : _habit.Icon };
        PageTitle.Text = existing == null ? "New habit" : "Edit habit";
        DeleteButton.Visibility = existing == null ? Visibility.Collapsed : Visibility.Visible;
        Populate();
        NameBox.Focus(FocusState.Programmatic);
    }

    private void Populate()
    {
        _loading = true;
        var h = _habit;
        NameBox.Text = h.Name;
        NoteBox.Text = h.Note;
        ColorGrid.SelectedIndex = ((h.ColorIndex % Colors.Count) + Colors.Count) % Colors.Count;
        var glyph = HabitVisuals.Glyph(h.Icon);
        IconGrid.SelectedItem = HabitVisuals.Choices.FirstOrDefault(c => c.Glyph == glyph) ?? HabitVisuals.Choices[0];
        TypeButtons.SelectedIndex = (int)h.Type;
        GoalBox.Value = h.Type == HabitType.CHECK ? 1 : h.Goal;
        UnitBox.Text = h.Unit;
        ScheduleButtons.SelectedIndex = (int)h.ScheduleType;
        _daysMask = h.DaysMask & DaysMask.ALL;
        TimesBox.Value = h.TimesPerPeriod;
        StartPicker.Date = new DateTimeOffset(h.StartDate.ToDateTime(TimeOnly.MinValue));

        var groups = new List<GroupItem> { new(null, "No group") };
        groups.AddRange(AppServices.Store.GetGroups().Select(g => new GroupItem(g.Id, g.Name)));
        GroupBox.ItemsSource = groups;
        GroupBox.SelectedItem = groups.FirstOrDefault(g => g.Id == h.GroupId) ?? groups[0];

        var outgoing = AppServices.Sharing.State.Outgoing;
        ShareSwitch.Visibility = outgoing != null ? Visibility.Visible : Visibility.Collapsed;
        ShareSwitch.IsOn = outgoing == null || !outgoing.ExcludedHabitIds.Contains(h.Id);

        _reminders = h.Reminders.ToList();
        BuildDayToggles();
        BuildReminders();
        _loading = false;
        UpdateState();
    }

    private void BuildDayToggles()
    {
        DayToggles.Children.Clear();
        var culture = CultureInfo.CurrentCulture;
        foreach (var day in AppServices.Logic.WeekDays(EpochDay.Today()).Select(d => d.DayOfWeek))
        {
            var toggle = new ToggleButton
            {
                Content = culture.DateTimeFormat.GetAbbreviatedDayName(day),
                IsChecked = DaysMask.Contains(_daysMask, day),
                MinWidth = 52,
            };
            toggle.Click += (_, _) =>
            {
                _daysMask = toggle.IsChecked == true ? DaysMask.With(_daysMask, day) : _daysMask & ~(DaysMask.With(0, day));
                UpdateState();
            };
            DayToggles.Children.Add(toggle);
        }
    }

    private void BuildReminders()
    {
        ReminderList.Children.Clear();
        for (var i = 0; i < _reminders.Count; i++)
        {
            var index = i;
            var r = _reminders[i];
            var picker = new TimePicker { Time = new TimeSpan(r.Hour, r.Minute, 0), MinuteIncrement = 5 };
            picker.SelectedTimeChanged += (_, e) =>
            {
                if (e.NewTime is { } t) _reminders[index] = _reminders[index] with { Hour = t.Hours, Minute = t.Minutes };
            };
            var toggle = new ToggleSwitch { IsOn = r.Enabled, OnContent = "On", OffContent = "Off", MinWidth = 0 };
            toggle.Toggled += (_, _) => _reminders[index] = _reminders[index] with { Enabled = toggle.IsOn };
            var remove = new Button
            {
                Content = new FontIcon { Glyph = "", FontSize = 14 },
                Style = (Style)Application.Current.Resources["IconButtonStyle"],
                Background = new SolidColorBrush(Microsoft.UI.Colors.Transparent), BorderThickness = new Thickness(0),
            };
            Microsoft.UI.Xaml.Automation.AutomationProperties.SetName(remove, "Remove reminder");
            ToolTipService.SetToolTip(remove, "Remove reminder");
            remove.Click += (_, _) => { _reminders.RemoveAt(index); BuildReminders(); };

            var row = new StackPanel { Orientation = Orientation.Horizontal, Spacing = 12 };
            row.Children.Add(picker);
            row.Children.Add(toggle);
            row.Children.Add(remove);
            if ((r.DaysMask & DaysMask.ALL) != DaysMask.ALL)
                row.Children.Add(new TextBlock
                {
                    Text = string.Join(" ", DaysMask.Days(r.DaysMask).Select(d => CultureInfo.CurrentCulture.DateTimeFormat.GetAbbreviatedDayName(d))),
                    VerticalAlignment = VerticalAlignment.Center, Style = (Style)Application.Current.Resources["SecondaryTextStyle"],
                });
            ReminderList.Children.Add(row);
        }
    }

    private void OnAddReminder(object sender, RoutedEventArgs e)
    {
        var last = _reminders.LastOrDefault();
        _reminders.Add(new Reminder { HabitId = _habit.Id, Hour = last != null ? Math.Min(last.Hour + 3, 22) : 9, Minute = 0 });
        BuildReminders();
    }

    private void OnChanged(object sender, object e) => UpdateState();

    private void OnGoalChanged(NumberBox sender, NumberBoxValueChangedEventArgs args) => UpdateState();

    private HabitType SelectedType => (HabitType)Math.Max(TypeButtons.SelectedIndex, 0);
    private ScheduleType SelectedSchedule => (ScheduleType)Math.Max(ScheduleButtons.SelectedIndex, 0);

    private void UpdateState()
    {
        if (_loading) return;
        var type = SelectedType;
        if (type != HabitType.CHECK && (double.IsNaN(GoalBox.Value) || (_habit.Type == HabitType.CHECK && GoalBox.Value <= 1)))
        {
            // Sensible defaults when switching kind, as on Android: 8 of something, or 20 minutes.
            _loading = true;
            GoalBox.Value = type == HabitType.TIMER ? 20 : 8;
            _loading = false;
        }
        GoalPanel.Visibility = type == HabitType.CHECK ? Visibility.Collapsed : Visibility.Visible;
        UnitBox.Visibility = type == HabitType.COUNT ? Visibility.Visible : Visibility.Collapsed;
        GoalBox.Header = type == HabitType.TIMER ? "Minutes per day" : "Goal per day";

        var schedule = SelectedSchedule;
        DaysPanel.Visibility = schedule == ScheduleType.DAILY ? Visibility.Visible : Visibility.Collapsed;
        TimesBox.Visibility = schedule == ScheduleType.DAILY ? Visibility.Collapsed : Visibility.Visible;
        TimesBox.Header = schedule == ScheduleType.WEEKLY ? "Times per week" : "Times per month";
        TimesBox.Maximum = schedule == ScheduleType.WEEKLY ? 7 : 31;

        var draft = Draft();
        PreviewCircle.Fill = HabitVisuals.BrushOf(draft.ColorIndex);
        PreviewIcon.Glyph = HabitVisuals.Glyph(draft.Icon);
        PreviewSubtitle.Text = $"{draft.GoalLabel()} · {draft.ScheduleLabel()}";
        SaveButton.IsEnabled = draft.Name.Trim().Length > 0 && (schedule != ScheduleType.DAILY || _daysMask != 0);
    }

    private Habit Draft()
    {
        var type = SelectedType;
        var goal = double.IsNaN(GoalBox.Value) ? 1 : GoalBox.Value;
        return _habit with
        {
            Name = NameBox.Text,
            Note = NoteBox.Text.Trim(),
            ColorIndex = Math.Max(ColorGrid.SelectedIndex, 0),
            Icon = (IconGrid.SelectedItem as IconChoice)?.Key ?? "sun",
            Type = type,
            Goal = type == HabitType.CHECK ? 1 : Math.Max(goal, 0.1),
            Unit = type == HabitType.TIMER ? "min" : type == HabitType.COUNT ? UnitBox.Text.Trim() : "",
            ScheduleType = SelectedSchedule,
            DaysMask = _daysMask,
            TimesPerPeriod = double.IsNaN(TimesBox.Value) ? 1 : (int)Math.Max(TimesBox.Value, 1),
            StartDate = StartPicker.Date is { } d ? DateOnly.FromDateTime(d.Date) : EpochDay.Today(),
            GroupId = (GroupBox.SelectedItem as GroupItem)?.Id,
            Reminders = _reminders.ToList(),
        };
    }

    private async void OnNewGroup(object sender, RoutedEventArgs e)
    {
        var name = await Dialogs.Prompt(XamlRoot, "New group", "Name", placeholder: "e.g. Morning", action: "Create");
        if (name == null) return;
        var id = AppServices.Store.CreateGroup(name);
        var items = (List<GroupItem>)GroupBox.ItemsSource;
        var item = new GroupItem(id, name);
        items.Add(item);
        GroupBox.ItemsSource = null;
        GroupBox.ItemsSource = items;
        GroupBox.SelectedItem = item;
    }

    private async void OnSave(object sender, RoutedEventArgs e)
    {
        var id = AppServices.Store.SaveHabit(Draft());
        if (ShareSwitch.Visibility == Visibility.Visible)
        {
            var excluded = AppServices.Sharing.State.Outgoing?.ExcludedHabitIds.Contains(id) == true;
            if (excluded == ShareSwitch.IsOn) await AppServices.Sharing.SetHabitShared(id, ShareSwitch.IsOn);
        }
        if (Frame.CanGoBack) Frame.GoBack();
    }

    private void OnCancel(object sender, RoutedEventArgs e)
    {
        if (Frame.CanGoBack) Frame.GoBack();
    }

    private async void OnDelete(object sender, RoutedEventArgs e)
    {
        if (!await Dialogs.Confirm(XamlRoot, $"Delete {_habit.Name}?", "Its history will be deleted too. Archive it instead if you might want it back.", "Delete"))
            return;
        AppServices.Store.DeleteHabit(_habit.Id);
        // Skip the deleted habit's detail page on the way back.
        while (Frame.BackStack.Count > 0 && Frame.BackStack[^1].SourcePageType == typeof(HabitDetailPage)) Frame.BackStack.RemoveAt(Frame.BackStack.Count - 1);
        if (Frame.CanGoBack) Frame.GoBack();
    }
}
