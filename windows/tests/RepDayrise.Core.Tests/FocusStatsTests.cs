using RepDayrise.Core.Domain;
using RepDayrise.Core.Models;

namespace RepDayrise.Core.Tests;

public class FocusStatsTests
{
    private static readonly DateOnly Today = new(2026, 11, 8); // a Sunday

    private static FocusSession S(int daysAgo, double minutes, long? habit = null, int hour = 9) => new()
    {
        Date = Today.AddDays(-daysAgo),
        FocusedMs = (long)(minutes * 60_000),
        HabitId = habit,
        StartedAt = new DateTimeOffset(Today.AddDays(-daysAgo).ToDateTime(new TimeOnly(hour, 0)), TimeSpan.Zero).ToUnixTimeMilliseconds(),
    };

    [Fact]
    public void Totals()
    {
        FocusSession[] sessions = [S(0, 25), S(0, 25), S(1, 50), S(10, 90)];
        var sum = FocusStats.Summary(sessions, Today, new HabitLogic(DayOfWeek.Monday), 25);
        Assert.Equal(50, sum.TodayMinutes, 6);
        Assert.Equal(2, sum.TodaySessions);
        Assert.Equal(100, sum.WeekMinutes, 6); // Mon 2 Nov .. Sun 8 Nov
        Assert.Equal(190, sum.TotalMinutes, 6);
        Assert.Equal(4, sum.TotalSessions);
    }

    [Fact]
    public void WeekFollowsTheWeekStart()
    {
        FocusSession[] sessions = [S(0, 25), S(1, 25)]; // Sunday and Saturday
        Assert.Equal(25, FocusStats.Summary(sessions, Today, new HabitLogic(DayOfWeek.Sunday), 25).WeekMinutes, 6);
    }

    [Fact]
    public void TodayDoesNotBreakTheStreakUntilItIsOver()
    {
        FocusSession[] sessions = [S(1, 30), S(2, 30), S(3, 30)];
        var sum = FocusStats.Summary(sessions, Today, new HabitLogic(), 25);
        Assert.Equal(3, sum.CurrentStreak);

        var withToday = FocusStats.Summary([.. sessions, S(0, 25)], Today, new HabitLogic(), 25);
        Assert.Equal(4, withToday.CurrentStreak);
    }

    [Fact]
    public void DaysUnderTheGoalBreakTheStreak()
    {
        FocusSession[] sessions = [S(1, 30), S(2, 10), S(3, 30), S(4, 30), S(5, 30)];
        var sum = FocusStats.Summary(sessions, Today, new HabitLogic(), 25);
        Assert.Equal(1, sum.CurrentStreak);
        Assert.Equal(3, sum.BestStreak);
    }

    [Fact]
    public void SeveralShortSessionsCanMeetTheGoalTogether()
    {
        var sum = FocusStats.Summary([S(0, 15), S(0, 15)], Today, new HabitLogic(), 25);
        Assert.Equal(1, sum.CurrentStreak);
    }

    [Fact]
    public void HeatmapIsAShareOfTheDailyGoalAlignedToTheGraph()
    {
        var logic = new HabitLogic(DayOfWeek.Monday);
        var from = logic.GraphStart(Today);
        Assert.Equal(DayOfWeek.Monday, from.DayOfWeek);
        Assert.Equal(HabitLogic.GraphWeeks * 7 - 1, Today.DayNumber - from.DayNumber); // Sunday ends the last column

        var map = FocusStats.Heatmap([S(0, 50), S(1, 10), S(400, 25)], from, Today, 50);
        Assert.Equal(1f, map[Today.ToEpochDay()]);
        Assert.Equal(0.2f, map[Today.AddDays(-1).ToEpochDay()], 4);
        Assert.Equal(2, map.Count); // the old session is outside the range
        Assert.Equal(DayOfWeek.Sunday, new HabitLogic(DayOfWeek.Sunday).GraphStart(Today).DayOfWeek);
    }

    [Fact]
    public void BreaksDownByHabitAndHour()
    {
        FocusSession[] sessions = [S(0, 25, 1, 9), S(0, 50, 2, 14), S(1, 25, 1, 9), S(2, 10, null, 22)];
        var byHabit = FocusStats.ByHabit(sessions);
        Assert.Equal((1L, 50.0), (byHabit[0].HabitId, byHabit[0].Minutes));
        Assert.Contains(byHabit, x => x.HabitId == null && Math.Abs(x.Minutes - 10) < 1e-6);

        var byHour = FocusStats.ByHour(sessions, ms => DateTimeOffset.FromUnixTimeMilliseconds(ms).UtcDateTime.Hour);
        Assert.Equal(50, byHour[9], 6);
        Assert.Equal(50, byHour[14], 6);
        Assert.Equal(10, byHour[22], 6);
    }

    [Fact]
    public void LastDaysIsOldestFirst()
    {
        var days = FocusStats.LastDays([S(0, 25), S(6, 10)], Today, 7);
        Assert.Equal([10, 0, 0, 0, 0, 0, 25], days);
    }
}
