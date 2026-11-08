using RepDayrise.Core.Models;

namespace RepDayrise.Core.Domain;

public sealed record FocusSummary(
    double TodayMinutes, int TodaySessions, double WeekMinutes, double TotalMinutes, int TotalSessions,
    int CurrentStreak, int BestStreak);

/// <summary>Numbers for the focus dashboard, computed from recorded sessions. Pure.</summary>
public static class FocusStats
{
    public static Dictionary<long, double> MinutesByDay(IEnumerable<FocusSession> sessions)
    {
        var map = new Dictionary<long, double>();
        foreach (var s in sessions)
        {
            var day = s.Date.ToEpochDay();
            map[day] = map.GetValueOrDefault(day) + s.FocusedMinutes;
        }
        return map;
    }

    public static Dictionary<long, int> SessionsByDay(IEnumerable<FocusSession> sessions)
    {
        var map = new Dictionary<long, int>();
        foreach (var s in sessions)
        {
            var day = s.Date.ToEpochDay();
            map[day] = map.GetValueOrDefault(day) + 1;
        }
        return map;
    }

    /// <summary>
    /// Totals and the focus streak: consecutive days with at least <paramref name="dailyGoalMinutes"/> focused.
    /// Today doesn't break the streak until it's over, the same rule as habit streaks.
    /// </summary>
    public static FocusSummary Summary(IReadOnlyCollection<FocusSession> sessions, DateOnly today, HabitLogic logic, double dailyGoalMinutes)
    {
        var minutes = MinutesByDay(sessions);
        var sessionsByDay = SessionsByDay(sessions);
        var t = today.ToEpochDay();
        var weekStart = logic.WeekStartOf(today).ToEpochDay();
        var goal = Math.Max(dailyGoalMinutes, 1);
        bool Met(long day) => minutes.GetValueOrDefault(day) + 1e-9 >= goal;

        var current = 0;
        for (var d = Met(t) ? t : t - 1; Met(d); d--) current++;

        int best = 0, run = 0;
        long? previous = null;
        foreach (var day in minutes.Keys.Where(Met).Order())
        {
            run = previous == day - 1 ? run + 1 : 1;
            best = Math.Max(best, run);
            previous = day;
        }

        return new FocusSummary(
            TodayMinutes: minutes.GetValueOrDefault(t),
            TodaySessions: sessionsByDay.GetValueOrDefault(t),
            WeekMinutes: minutes.Where(kv => kv.Key >= weekStart && kv.Key <= t).Sum(kv => kv.Value),
            TotalMinutes: minutes.Values.Sum(),
            TotalSessions: sessions.Count,
            CurrentStreak: current,
            BestStreak: Math.Max(best, current));
    }

    /// <summary>Each day in [from, to] as a 0..1 share of the daily goal, for the year graph. Empty days are left out.</summary>
    public static Dictionary<long, float> Heatmap(IEnumerable<FocusSession> sessions, DateOnly from, DateOnly to, double dailyGoalMinutes)
    {
        var goal = Math.Max(dailyGoalMinutes, 1);
        var (f, t) = (from.ToEpochDay(), to.ToEpochDay());
        return MinutesByDay(sessions)
            .Where(kv => kv.Key >= f && kv.Key <= t && kv.Value > 0)
            .ToDictionary(kv => kv.Key, kv => (float)Math.Clamp(kv.Value / goal, 0, 1));
    }

    /// <summary>Focused minutes per habit (null key = untagged), most first.</summary>
    public static IReadOnlyList<(long? HabitId, double Minutes)> ByHabit(IEnumerable<FocusSession> sessions) =>
        sessions.GroupBy(s => s.HabitId)
            .Select(g => (g.Key, g.Sum(s => s.FocusedMinutes)))
            .OrderByDescending(x => x.Item2)
            .ToList();

    /// <summary>Focused minutes by hour of the local day the session started.</summary>
    public static double[] ByHour(IEnumerable<FocusSession> sessions, Func<long, int>? hourOf = null)
    {
        hourOf ??= ms => DateTimeOffset.FromUnixTimeMilliseconds(ms).LocalDateTime.Hour;
        var hours = new double[24];
        foreach (var s in sessions) hours[hourOf(s.StartedAt)] += s.FocusedMinutes;
        return hours;
    }

    /// <summary>Minutes for each of the last <paramref name="days"/> days ending today, oldest first.</summary>
    public static double[] LastDays(IEnumerable<FocusSession> sessions, DateOnly today, int days)
    {
        var minutes = MinutesByDay(sessions);
        var t = today.ToEpochDay();
        return Enumerable.Range(0, days).Select(i => minutes.GetValueOrDefault(t - (days - 1) + i)).ToArray();
    }
}
