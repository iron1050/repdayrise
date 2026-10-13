using System.Collections.ObjectModel;
using CommunityToolkit.Mvvm.ComponentModel;
using Microsoft.UI.Xaml.Media;
using RepDayrise.Core.Domain;
using RepDayrise.Core.Models;
using RepDayrise.Services;

namespace RepDayrise.ViewModels;

/// <summary>What a habit row asks its page to do.</summary>
public interface IHabitRowHost
{
    void OpenHabit(HabitRowViewModel row);
    void EditHabit(HabitRowViewModel row);
    Task SetValue(HabitRowViewModel row);
    Task ArchiveHabit(HabitRowViewModel row);
    Task DeleteHabit(HabitRowViewModel row);
}

/// <summary>One habit on the Today list for a given day.</summary>
public sealed partial class HabitRowViewModel(IHabitRowHost host) : ObservableObject
{
    public Habit Habit { get; private set; } = null!;
    public DateOnly Date { get; private set; }
    public long Id => Habit.Id;

    [ObservableProperty] public partial string Name { get; set; } = "";
    [ObservableProperty] public partial string Subtitle { get; set; } = "";
    [ObservableProperty] public partial string Glyph { get; set; } = "";
    [ObservableProperty] public partial SolidColorBrush ColorBrush { get; set; } = new();
    [ObservableProperty] public partial double Percent { get; set; }
    [ObservableProperty] public partial bool IsDone { get; set; }
    [ObservableProperty] public partial bool IsCheck { get; set; }
    [ObservableProperty] public partial bool IsCount { get; set; }
    [ObservableProperty] public partial bool IsTimer { get; set; }
    [ObservableProperty] public partial bool IsMeasurable { get; set; }
    [ObservableProperty] public partial string TimerText { get; set; } = "";
    [ObservableProperty] public partial bool TimerRunning { get; set; }
    [ObservableProperty] public partial bool TimerActive { get; set; }
    [ObservableProperty] public partial string TimerGlyph { get; set; } = "";
    [ObservableProperty] public partial string CheckLabel { get; set; } = "";
    [ObservableProperty] public partial SolidColorBrush CheckBackground { get; set; } = new();
    [ObservableProperty] public partial double CheckIconOpacity { get; set; }

    private ActiveTimer? _timer;

    public void Update(Habit habit, DateOnly date, EntryMap entries, ActiveTimer? timer, HabitLogic logic)
    {
        Habit = habit;
        Date = date;
        _timer = timer is { } t && t.Date == date ? t : null;
        var value = logic.Value(entries, habit.Id, date);
        var color = HabitVisuals.ColorOf(habit.ColorIndex);

        Name = habit.Name;
        Glyph = HabitVisuals.Glyph(habit.Icon);
        if (ColorBrush.Color != color) ColorBrush = new SolidColorBrush(color);
        IsCheck = habit.Type == HabitType.CHECK;
        IsCount = habit.Type == HabitType.COUNT;
        IsTimer = habit.Type == HabitType.TIMER;
        IsMeasurable = habit.IsMeasurable;
        IsDone = logic.IsCompleted(habit, value);
        Percent = logic.Fraction(habit, value) * 100;
        CheckBackground = new SolidColorBrush(IsDone ? color : HabitVisuals.WithAlpha(color, 0.12));
        CheckIconOpacity = IsDone ? 1 : 0.0;
        CheckLabel = IsDone ? $"Mark {habit.Name} not done" : $"Mark {habit.Name} done";

        var parts = new List<string>();
        switch (habit.Type)
        {
            case HabitType.COUNT:
                parts.Add($"{Format.Value(Math.Round(value, 1))} / {habit.GoalLabel()}");
                break;
            case HabitType.TIMER:
                parts.Add($"{Format.Duration(value)} of {Format.Duration(habit.Goal)}");
                break;
        }
        if (habit.ScheduleType == ScheduleType.DAILY)
        {
            if ((habit.DaysMask & DaysMask.ALL) != DaysMask.ALL || habit.Type == HabitType.CHECK) parts.Add(habit.ScheduleLabel());
        }
        else
        {
            var done = logic.CompletedCountInPeriod(habit, entries, date);
            parts.Add($"{Math.Min(done, habit.TimesPerPeriod)} of {habit.TimesPerPeriod} this {(habit.ScheduleType == ScheduleType.WEEKLY ? "week" : "month")}");
        }
        Subtitle = string.Join(" · ", parts);
        Tick();
    }

    /// <summary>Refreshes the running timer's clock.</summary>
    public void Tick()
    {
        TimerActive = _timer != null;
        TimerRunning = _timer?.Running == true;
        TimerGlyph = TimerRunning ? "" : "";
        if (_timer == null) { TimerText = ""; return; }
        var elapsed = TimeSpan.FromMilliseconds(_timer.ElapsedMs());
        TimerText = elapsed.TotalHours >= 1 ? elapsed.ToString(@"h\:mm\:ss") : elapsed.ToString(@"m\:ss");
    }

    // ---- Actions (bound with x:Bind) ------------------------------------------------------

    public void Open() => host.OpenHabit(this);
    public void Edit() => host.EditHabit(this);
    public async void SetExact() => await host.SetValue(this);
    public async void Archive() => await host.ArchiveHabit(this);
    public async void Delete() => await host.DeleteHabit(this);

    public void ToggleCheck() => AppServices.Store.Toggle(Habit, Date);

    public void Increment() => AppServices.Store.Increment(Habit, Date, 1);

    public void Decrement() => AppServices.Store.Increment(Habit, Date, -1);

    public void ToggleTimer()
    {
        if (_timer?.Running == true) AppServices.Store.PauseTimer(Habit.Id);
        else AppServices.Store.StartTimer(Habit, Date);
    }

    public void FinishTimer() => AppServices.Store.FinishTimer(Habit);

    public void CancelTimer() => AppServices.Store.CancelTimer(Habit.Id);
}

/// <summary>A group of habits on the Today list. Ungrouped habits form a section without a header.</summary>
public sealed partial class HabitSectionViewModel : ObservableObject
{
    public long? GroupId { get; init; }
    public bool HasHeader => GroupId != null;

    [ObservableProperty] public partial string Title { get; set; } = "";
    [ObservableProperty] public partial string Summary { get; set; } = "";
    [ObservableProperty]
    [NotifyPropertyChangedFor(nameof(IsExpanded), nameof(ChevronGlyph))]
    public partial bool IsCollapsed { get; set; }

    public bool IsExpanded => !IsCollapsed;
    public string ChevronGlyph => IsCollapsed ? "" : "";

    public ObservableCollection<HabitRowViewModel> Rows { get; } = [];

    public void ToggleCollapsed()
    {
        IsCollapsed = !IsCollapsed;
        if (GroupId is { } id) AppServices.Store.SetGroupCollapsed(id, IsCollapsed);
    }
}

/// <summary>A day in the week strip above the list.</summary>
public sealed partial class DayChipViewModel : ObservableObject
{
    public DateOnly Date { get; init; }
    public string DayName { get; init; } = "";
    public string DayNumber { get; init; } = "";
    public bool IsFuture { get; init; }
    [ObservableProperty] public partial double Percent { get; set; }
    [ObservableProperty] public partial bool IsSelected { get; set; }
    [ObservableProperty] public partial bool IsToday { get; set; }
}
