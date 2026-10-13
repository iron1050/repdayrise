using RepDayrise.Core.Models;

namespace RepDayrise.Core.Domain;

public sealed record HabitStats(int CurrentStreak, int BestStreak, int TotalDays, string StreakUnit); // "days", "weeks", "months"

public sealed record DayStatus(DateOnly Date, double Value, float Fraction, bool Scheduled, bool Completed);

/// <summary>The streak and scheduling rules. A line-for-line port of the Android app's domain/HabitLogic.kt.</summary>
public sealed class HabitLogic(DayOfWeek weekStart = DayOfWeek.Monday)
{
    /// <summary>Columns on the year graph: 53 weeks always covers a full year whatever weekday today is.</summary>
    public const int GraphWeeks = 53;

    public DayOfWeek WeekStart { get; } = weekStart;

    public double Value(EntryMap entries, long habitId, DateOnly date) =>
        entries.TryGetValue(habitId, out var days) && days.TryGetValue(date.ToEpochDay(), out var v) ? v : 0.0;

    public float Fraction(Habit habit, double value) => Math.Clamp((float)(value / habit.EffectiveGoal), 0f, 1f);

    public bool IsCompleted(Habit habit, double value) => value + 1e-9 >= habit.EffectiveGoal;

    public bool IsCompleted(Habit habit, EntryMap entries, DateOnly date) => IsCompleted(habit, Value(entries, habit.Id, date));

    /// <summary>For daily habits: whether the weekday is part of the schedule. Weekly/monthly habits can be done any day.</summary>
    public bool IsScheduledDay(Habit habit, DateOnly date)
    {
        if (date < habit.StartDate) return false;
        return habit.ScheduleType != ScheduleType.DAILY || DaysMask.Contains(habit.DaysMask, date.DayOfWeek);
    }

    public DateOnly WeekStartOf(DateOnly date) => date.AddDays(-(((int)date.DayOfWeek - (int)WeekStart + 7) % 7));
    public DateOnly WeekEndOf(DateOnly date) => WeekStartOf(date).AddDays(6);

    public IReadOnlyList<DateOnly> WeekDays(DateOnly anchor)
    {
        var start = WeekStartOf(anchor);
        return Enumerable.Range(0, 7).Select(start.AddDays).ToList();
    }

    public int CompletedCountInPeriod(Habit habit, EntryMap entries, DateOnly date)
    {
        var (start, end) = PeriodRange(habit, date);
        var count = 0;
        for (var d = start; d <= end; d = d.AddDays(1))
            if (IsCompleted(habit, entries, d)) count++;
        return count;
    }

    public (DateOnly Start, DateOnly End) PeriodRange(Habit habit, DateOnly date) => habit.ScheduleType switch
    {
        ScheduleType.WEEKLY => (WeekStartOf(date), WeekEndOf(date)),
        ScheduleType.MONTHLY => (new DateOnly(date.Year, date.Month, 1), new DateOnly(date.Year, date.Month, DateTime.DaysInMonth(date.Year, date.Month))),
        _ => (date, date),
    };

    /// <summary>
    /// Whether the habit should be shown as "due" on <paramref name="date"/> in the daily list.
    /// Daily habits: scheduled weekdays. Weekly/monthly: due until the period goal is met, or if done that day.
    /// </summary>
    public bool IsDue(Habit habit, EntryMap entries, DateOnly date)
    {
        if (date < habit.StartDate) return false;
        if (habit.ScheduleType == ScheduleType.DAILY) return DaysMask.Contains(habit.DaysMask, date.DayOfWeek);
        if (IsCompleted(habit, entries, date)) return true;
        return CompletedCountInPeriod(habit, entries, date) < habit.TimesPerPeriod;
    }

    /// <summary>Per-day progress in [0,1] used for the sunrise.</summary>
    public float DayProgress(IEnumerable<Habit> habits, EntryMap entries, DateOnly date)
    {
        var due = habits.Where(h => !h.Archived && IsDue(h, entries, date)).ToList();
        if (due.Count == 0) return 0f;
        var total = due.Sum(h => (double)Fraction(h, Value(entries, h.Id, date)));
        return Math.Clamp((float)(total / due.Count), 0f, 1f);
    }

    public (int Done, int Due) DayCounts(IEnumerable<Habit> habits, EntryMap entries, DateOnly date)
    {
        var due = habits.Where(h => !h.Archived && IsDue(h, entries, date)).ToList();
        return (due.Count(h => IsCompleted(h, entries, date)), due.Count);
    }

    public HabitStats Stats(Habit habit, EntryMap entries, DateOnly? today = null)
    {
        var t = today ?? EpochDay.Today();
        var totalDays = Days(entries, habit.Id).Count(kv => EpochDay.ToDate(kv.Key) <= t && IsCompleted(habit, kv.Value));
        return habit.ScheduleType switch
        {
            ScheduleType.DAILY => DailyStats(habit, entries, t, totalDays),
            ScheduleType.WEEKLY => PeriodStats(habit, entries, t, totalDays, "weeks"),
            _ => PeriodStats(habit, entries, t, totalDays, "months"),
        };
    }

    private HabitStats DailyStats(Habit habit, EntryMap entries, DateOnly today, int totalDays)
    {
        var start = habit.StartDate;
        // Current streak: walk backwards from today. Today counts if done; if not done yet, it doesn't break the streak.
        var current = 0;
        var d = today;
        var first = true;
        while (d >= start)
        {
            if (IsScheduledDay(habit, d))
            {
                if (IsCompleted(habit, entries, d)) current++;
                else if (!first) break;
                first = false;
            }
            d = d.AddDays(-1);
            if (today.DayNumber - d.DayNumber > 3660) break;
        }
        // Best streak: scan forward.
        int best = 0, run = 0;
        for (var s = start; s <= today; s = s.AddDays(1))
        {
            if (!IsScheduledDay(habit, s)) continue;
            if (IsCompleted(habit, entries, s)) { run++; if (run > best) best = run; }
            else if (s != today) run = 0;
        }
        return new HabitStats(current, Math.Max(best, current), totalDays, "days");
    }

    private HabitStats PeriodStats(Habit habit, EntryMap entries, DateOnly today, int totalDays, string unit)
    {
        var periods = new List<DateOnly>(); // period anchors from start to today
        var anchor = PeriodRange(habit, habit.StartDate).Start;
        var todayAnchor = PeriodRange(habit, today).Start;
        var guard = 0;
        while (anchor <= todayAnchor && guard++ < 2000)
        {
            periods.Add(anchor);
            anchor = habit.ScheduleType == ScheduleType.WEEKLY ? anchor.AddDays(7) : anchor.AddMonths(1);
        }
        var met = periods.Select(p => CompletedCountInPeriod(habit, entries, p) >= habit.TimesPerPeriod).ToList();
        var current = 0;
        for (var i = met.Count - 1; i >= 0; i--)
        {
            if (met[i]) current++;
            else if (i != met.Count - 1) break;
        }
        int best = 0, run = 0;
        for (var i = 0; i < met.Count; i++)
        {
            if (met[i]) { run++; best = Math.Max(best, run); }
            else if (i != met.Count - 1) run = 0;
        }
        return new HabitStats(current, Math.Max(best, current), totalDays, unit);
    }

    public DayStatus DayStatus(Habit habit, EntryMap entries, DateOnly date)
    {
        var v = Value(entries, habit.Id, date);
        return new DayStatus(date, v, Fraction(habit, v), IsScheduledDay(habit, date), IsCompleted(habit, v));
    }

    /// <summary>Completion ratio for the month containing <paramref name="month"/>: completed days / expected days.</summary>
    public float MonthCompletion(Habit habit, EntryMap entries, DateOnly month, DateOnly? today = null)
    {
        var t = today ?? EpochDay.Today();
        var start = new DateOnly(month.Year, month.Month, 1);
        var monthEnd = start.AddMonths(1).AddDays(-1);
        var end = monthEnd < t ? monthEnd : t;
        if (end < start || end < habit.StartDate) return 0f;
        var from = start > habit.StartDate ? start : habit.StartDate;
        switch (habit.ScheduleType)
        {
            case ScheduleType.DAILY:
            {
                int expected = 0, done = 0;
                for (var d = from; d <= end; d = d.AddDays(1))
                {
                    if (!IsScheduledDay(habit, d)) continue;
                    expected++;
                    if (IsCompleted(habit, entries, d)) done++;
                }
                return expected == 0 ? 0f : (float)done / expected;
            }
            case ScheduleType.WEEKLY:
            {
                int expected = 0, done = 0;
                for (var w = WeekStartOf(from); w <= end; w = w.AddDays(7))
                {
                    expected += habit.TimesPerPeriod;
                    done += Math.Min(CompletedCountInPeriod(habit, entries, w), habit.TimesPerPeriod);
                }
                return expected == 0 ? 0f : (float)done / expected;
            }
            default:
            {
                var done = Math.Min(CompletedCountInPeriod(habit, entries, start), habit.TimesPerPeriod);
                return (float)done / habit.TimesPerPeriod;
            }
        }
    }

    /// <summary>First day shown on a year graph of <paramref name="weeks"/> columns ending in the week of <paramref name="today"/>.</summary>
    public DateOnly GraphStart(DateOnly today, int weeks = GraphWeeks) => WeekStartOf(today).AddDays(-7 * (weeks - 1));

    /// <summary>epochDay -> fraction of the goal reached, for one habit, across an inclusive range.</summary>
    public Dictionary<long, float> HabitHeatmap(Habit habit, EntryMap entries, DateOnly from, DateOnly to)
    {
        long lo = from.ToEpochDay(), hi = to.ToEpochDay();
        var output = new Dictionary<long, float>();
        foreach (var (epoch, v) in Days(entries, habit.Id))
            if (epoch >= lo && epoch <= hi && v > 0.0) output[epoch] = Fraction(habit, v);
        return output;
    }

    /// <summary>epochDay -> overall day progress across <paramref name="habits"/>, for an inclusive range. Days with nothing logged are omitted.</summary>
    public Dictionary<long, float> OverallHeatmap(IEnumerable<Habit> habits, EntryMap entries, DateOnly from, DateOnly to)
    {
        long lo = from.ToEpochDay(), hi = to.ToEpochDay();
        var active = habits.Where(h => !h.Archived).ToList();
        var days = new HashSet<long>();
        foreach (var h in active)
            foreach (var (epoch, v) in Days(entries, h.Id))
                if (epoch >= lo && epoch <= hi && v > 0.0) days.Add(epoch);
        var output = new Dictionary<long, float>();
        foreach (var epoch in days)
        {
            var p = DayProgress(active, entries, EpochDay.ToDate(epoch));
            if (p > 0f) output[epoch] = p;
        }
        return output;
    }

    /// <summary>Count of completed days in an inclusive date range.</summary>
    public int CompletedInRange(Habit habit, EntryMap entries, DateOnly from, DateOnly to) =>
        Days(entries, habit.Id).Count(kv =>
        {
            var d = EpochDay.ToDate(kv.Key);
            return d >= from && d <= to && IsCompleted(habit, kv.Value);
        });

    public double SumInRange(Habit habit, EntryMap entries, DateOnly from, DateOnly to) =>
        Days(entries, habit.Id).Sum(kv =>
        {
            var d = EpochDay.ToDate(kv.Key);
            return d >= from && d <= to ? kv.Value : 0.0;
        });

    private static IReadOnlyDictionary<long, double> Days(EntryMap entries, long habitId) =>
        entries.TryGetValue(habitId, out var days) ? days : Empty;

    private static readonly Dictionary<long, double> Empty = new();
}

/// <summary>Buckets a fraction into the five shades of the year graph, like GitHub's contribution graph.</summary>
public static class Contribution
{
    public const int Levels = 4;

    public static int Level(float fraction) =>
        fraction <= 0f ? 0
        : fraction >= 0.999f ? Levels
        : 1 + Math.Clamp((int)(fraction * (Levels - 1)), 0, Levels - 2);
}
