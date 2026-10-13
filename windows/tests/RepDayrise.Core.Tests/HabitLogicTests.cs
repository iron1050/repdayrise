using RepDayrise.Core.Domain;
using RepDayrise.Core.Models;

namespace RepDayrise.Core.Tests;

/// <summary>Port of the Android HabitLogicTest.kt; the expectations must stay identical.</summary>
public class HabitLogicTests
{
    private readonly HabitLogic _logic = new(DayOfWeek.Monday);
    private static readonly DateOnly Today = new(2026, 9, 15); // Tuesday

    private static EntryMap Entries(long habitId, double value, params DateOnly[] dates) =>
        new() { [habitId] = dates.ToDictionary(d => d.ToEpochDay(), _ => value) };

    private static EntryMap Entries(long habitId, params DateOnly[] dates) => Entries(habitId, 1.0, dates);

    [Fact]
    public void DailyStreakCountsConsecutiveDaysAndToleratesTodayUnfinished()
    {
        var habit = new Habit { Id = 1, Name = "Read", StartDate = Today.AddDays(-10) };
        var e = Entries(1, Today.AddDays(-1), Today.AddDays(-2), Today.AddDays(-3));
        var stats = _logic.Stats(habit, e, Today);
        Assert.Equal(3, stats.CurrentStreak);
        Assert.Equal(3, stats.BestStreak);
        Assert.Equal(3, stats.TotalDays);
    }

    [Fact]
    public void DailyStreakBreaksOnMissedScheduledDay()
    {
        var habit = new Habit { Id = 1, Name = "Read", StartDate = Today.AddDays(-10) };
        var e = Entries(1, Today, Today.AddDays(-2), Today.AddDays(-3));
        Assert.Equal(1, _logic.Stats(habit, e, Today).CurrentStreak);
        Assert.Equal(2, _logic.Stats(habit, e, Today).BestStreak);
    }

    [Fact]
    public void WeekdayOnlyHabitSkipsWeekends()
    {
        var habit = new Habit { Id = 1, Name = "Gym", DaysMask = DaysMask.WEEKDAYS, StartDate = Today.AddDays(-20) };
        // Mon 14, Fri 11, Thu 10 done; weekend 12-13 unscheduled
        var e = Entries(1, Today.AddDays(-1), Today.AddDays(-4), Today.AddDays(-5));
        Assert.Equal(3, _logic.Stats(habit, e, Today).CurrentStreak);
    }

    [Fact]
    public void MeasurableProgressIsFractional()
    {
        var habit = new Habit { Id = 1, Name = "Water", Type = HabitType.COUNT, Goal = 8.0, Unit = "glasses", StartDate = Today.AddDays(-10) };
        var e = Entries(1, 4.0, Today);
        Assert.Equal(0.5f, _logic.DayProgress([habit], e, Today), 3);
        Assert.False(_logic.IsCompleted(habit, e, Today));
    }

    [Fact]
    public void WeeklyHabitIsDueUntilGoalMet()
    {
        var habit = new Habit { Id = 1, Name = "Swim", ScheduleType = ScheduleType.WEEKLY, TimesPerPeriod = 2, StartDate = Today.AddDays(-30) };
        Assert.True(_logic.IsDue(habit, Entries(1, Today.AddDays(-1)), Today));
        Assert.True(_logic.IsDue(habit, Entries(1, Today.AddDays(-1), Today), Today)); // done today, still shown
        Assert.True(_logic.IsDue(habit, Entries(1, Today.AddDays(-1), Today.AddDays(-7)), Today));
    }

    [Fact]
    public void WeeklyHabitIsNotDueOnceGoalMetOnOtherDays()
    {
        var habit = new Habit { Id = 1, Name = "Swim", ScheduleType = ScheduleType.WEEKLY, TimesPerPeriod = 1, StartDate = Today.AddDays(-30) };
        Assert.False(_logic.IsDue(habit, Entries(1, Today.AddDays(-1)), Today));
    }

    [Fact]
    public void MoonPhaseIsInRange()
    {
        var p = MoonPhase.Phase(Today);
        Assert.InRange(p, 0.0, 0.999999);
        // 2026-09-26 is a near-full moon; phase should be around 0.5
        Assert.InRange(MoonPhase.Phase(new DateOnly(2026, 9, 26)), 0.4, 0.6);
    }

    [Fact]
    public void GraphStartIsAWeekStartFiftyTwoWeeksBack()
    {
        var start = _logic.GraphStart(Today);
        Assert.Equal(DayOfWeek.Monday, start.DayOfWeek);
        Assert.Equal(new DateOnly(2026, 9, 14).AddDays(-7 * 52), start);
    }

    [Fact]
    public void GraphStartHonoursASundayWeek()
    {
        var start = new HabitLogic(DayOfWeek.Sunday).GraphStart(Today);
        Assert.Equal(DayOfWeek.Sunday, start.DayOfWeek);
    }

    [Fact]
    public void HabitHeatmapReportsFractionsInsideTheRangeOnly()
    {
        var habit = new Habit { Id = 1, Name = "Water", Type = HabitType.COUNT, Goal = 8.0, StartDate = Today.AddDays(-400) };
        var e = new EntryMap
        {
            [1] = new()
            {
                [Today.ToEpochDay()] = 4.0,
                [Today.AddDays(-1).ToEpochDay()] = 12.0,
                [Today.AddDays(-500).ToEpochDay()] = 8.0,
            },
        };
        var map = _logic.HabitHeatmap(habit, e, _logic.GraphStart(Today), Today);
        Assert.Equal(2, map.Count);
        Assert.Equal(0.5f, map[Today.ToEpochDay()], 3);
        Assert.Equal(1f, map[Today.AddDays(-1).ToEpochDay()], 3);
    }

    [Fact]
    public void OverallHeatmapAveragesAcrossDueHabits()
    {
        var a = new Habit { Id = 1, Name = "Read", StartDate = Today.AddDays(-10) };
        var b = new Habit { Id = 2, Name = "Walk", StartDate = Today.AddDays(-10) };
        var retired = new Habit { Id = 3, Name = "Old", StartDate = Today.AddDays(-10), Archived = true };
        var e = new EntryMap
        {
            [1] = new() { [Today.ToEpochDay()] = 1.0, [Today.AddDays(-1).ToEpochDay()] = 1.0 },
            [2] = new() { [Today.ToEpochDay()] = 1.0 },
            [3] = new() { [Today.AddDays(-2).ToEpochDay()] = 1.0 },
        };
        var map = _logic.OverallHeatmap([a, b, retired], e, _logic.GraphStart(Today), Today);
        Assert.Equal(1f, map[Today.ToEpochDay()], 3);
        Assert.Equal(0.5f, map[Today.AddDays(-1).ToEpochDay()], 3);
        Assert.False(map.ContainsKey(Today.AddDays(-2).ToEpochDay()));
    }

    [Fact]
    public void ContributionLevelsBucketLikeAGithubGraph()
    {
        Assert.Equal(0, Contribution.Level(0f));
        Assert.Equal(1, Contribution.Level(0.1f));
        Assert.Equal(2, Contribution.Level(0.5f));
        Assert.Equal(3, Contribution.Level(0.9f));
        Assert.Equal(4, Contribution.Level(1f));
    }

    [Fact]
    public void DaysMaskUsesMondayAsBitZero()
    {
        Assert.True(DaysMask.Contains(0b0000001, DayOfWeek.Monday));
        Assert.True(DaysMask.Contains(0b1000000, DayOfWeek.Sunday));
        Assert.Equal(5, DaysMask.Count(DaysMask.WEEKDAYS));
        Assert.Equal("Mon Wed", new Habit { Name = "x", DaysMask = 0b0000101 }.ScheduleLabel());
    }

    [Fact]
    public void EpochDayMatchesJavaTime()
    {
        Assert.Equal(0, new DateOnly(1970, 1, 1).ToEpochDay());
        Assert.Equal(20711, new DateOnly(2026, 9, 15).ToEpochDay());
        Assert.Equal(new DateOnly(2026, 9, 15), EpochDay.ToDate(20711));
    }
}
