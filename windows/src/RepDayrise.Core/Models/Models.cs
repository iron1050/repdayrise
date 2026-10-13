using System.Globalization;
using System.Numerics;

namespace RepDayrise.Core.Models;

public enum HabitType { CHECK, COUNT, TIMER }

public enum ScheduleType { DAILY, WEEKLY, MONTHLY }

public enum ThemeMode { SYSTEM, LIGHT, DARK }

/// <summary>Bitmask helpers for weekday schedules: bit 0 = Monday … bit 6 = Sunday.</summary>
public static class DaysMask
{
    public const int ALL = 0b1111111;
    public const int WEEKDAYS = 0b0011111;
    public const int WEEKEND = 0b1100000;

    /// <summary>Monday-first order, matching java.time.DayOfWeek and the mask bits.</summary>
    public static readonly DayOfWeek[] Week =
        [DayOfWeek.Monday, DayOfWeek.Tuesday, DayOfWeek.Wednesday, DayOfWeek.Thursday, DayOfWeek.Friday, DayOfWeek.Saturday, DayOfWeek.Sunday];

    private static int Bit(DayOfWeek day) => day == DayOfWeek.Sunday ? 6 : (int)day - 1;

    public static bool Contains(int mask, DayOfWeek day) => ((mask >> Bit(day)) & 1) == 1;
    public static int Toggle(int mask, DayOfWeek day) => mask ^ (1 << Bit(day));
    public static int With(int mask, DayOfWeek day) => mask | (1 << Bit(day));
    public static int Count(int mask) => BitOperations.PopCount((uint)(mask & ALL));
    public static IReadOnlyList<DayOfWeek> Days(int mask) => Week.Where(d => Contains(mask, d)).ToList();
}

/// <summary>Converts between <see cref="DateOnly"/> and the epoch-day numbers stored on disk and sent to partners.</summary>
public static class EpochDay
{
    private static readonly int Offset = new DateOnly(1970, 1, 1).DayNumber;

    public static long Of(DateOnly date) => date.DayNumber - Offset;
    public static DateOnly ToDate(long epochDay) => DateOnly.FromDayNumber((int)(epochDay + Offset));
    public static long ToEpochDay(this DateOnly date) => Of(date);
    public static DateOnly Today() => DateOnly.FromDateTime(DateTime.Now);
}

public static class Clock
{
    public static long NowMs() => DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();
}

public sealed record Reminder
{
    public long Id { get; init; }
    /// <summary>Globally unique id so the reminder survives a future sync between devices.</summary>
    public string Uid { get; init; } = Guid.NewGuid().ToString();
    public long HabitId { get; init; }
    public int Hour { get; init; }
    public int Minute { get; init; }
    public int DaysMask { get; init; } = Models.DaysMask.ALL;
    public bool Enabled { get; init; } = true;

    public string TimeLabel()
    {
        var h12 = Hour % 12 == 0 ? 12 : Hour % 12;
        var suffix = Hour < 12 ? "AM" : "PM";
        return $"{h12}:{Minute:00} {suffix}";
    }
}

public sealed record HabitGroup
{
    public long Id { get; init; }
    public string Uid { get; init; } = Guid.NewGuid().ToString();
    public required string Name { get; init; }
    public int SortOrder { get; init; }
    public bool Collapsed { get; init; }
}

public sealed record Habit
{
    public long Id { get; init; }
    /// <summary>Globally unique id. Local <see cref="Id"/> stays because the partner snapshot format uses numbers.</summary>
    public string Uid { get; init; } = Guid.NewGuid().ToString();
    public required string Name { get; init; }
    public string Icon { get; init; } = "sun";
    public int ColorIndex { get; init; }
    public HabitType Type { get; init; } = HabitType.CHECK;
    public double Goal { get; init; } = 1.0;
    public string Unit { get; init; } = "";
    public ScheduleType ScheduleType { get; init; } = ScheduleType.DAILY;
    public int DaysMask { get; init; } = Models.DaysMask.ALL;
    public int TimesPerPeriod { get; init; } = 3;
    public long? GroupId { get; init; }
    public DateOnly StartDate { get; init; } = EpochDay.Today();
    public long CreatedAt { get; init; } = Clock.NowMs();
    public bool Archived { get; init; }
    public long? ArchivedAt { get; init; }
    public int SortOrder { get; init; }
    public string Note { get; init; } = "";
    public IReadOnlyList<Reminder> Reminders { get; init; } = [];

    public bool IsMeasurable => Type != HabitType.CHECK;
    public double EffectiveGoal => Type == HabitType.CHECK ? 1.0 : Math.Max(Goal, 0.0001);

    public string ScheduleLabel() => ScheduleType switch
    {
        ScheduleType.DAILY => (DaysMask & Models.DaysMask.ALL) switch
        {
            Models.DaysMask.ALL => "Every day",
            Models.DaysMask.WEEKDAYS => "Weekdays",
            Models.DaysMask.WEEKEND => "Weekends",
            _ => string.Join(" ", Models.DaysMask.Days(DaysMask).Select(d => d.ToString()[..3])),
        },
        ScheduleType.WEEKLY => $"{TimesPerPeriod}× a week",
        _ => $"{TimesPerPeriod}× a month",
    };

    public string GoalLabel() => Type switch
    {
        HabitType.CHECK => "Complete",
        HabitType.COUNT => $"{Format.Value(Goal)} {(string.IsNullOrWhiteSpace(Unit) ? "times" : Unit)}",
        _ => Format.Duration(Goal),
    };
}

/// <summary>A single day's record for a habit. Value is in the habit's unit (minutes for timers, 1 for checks).</summary>
public sealed record Entry(long HabitId, DateOnly Date, double Value);

public sealed record ActiveTimer(long HabitId, DateOnly Date, long StartedAt, long AccumulatedMs, bool Running)
{
    public long ElapsedMs(long? now = null) =>
        AccumulatedMs + (Running ? Math.Max((now ?? Clock.NowMs()) - StartedAt, 0) : 0);
}

public static class Format
{
    public static string Value(double v) =>
        v == Math.Floor(v) && v < 1e9 ? ((long)v).ToString(CultureInfo.InvariantCulture) : v.ToString("0.0", CultureInfo.InvariantCulture);

    /// <summary>Formats minutes as an h/m string.</summary>
    public static string Duration(double minutes)
    {
        var total = (int)minutes;
        int h = total / 60, m = total % 60;
        if (h > 0 && m > 0) return $"{h}h {m}m";
        return h > 0 ? $"{h}h" : $"{m}m";
    }
}
