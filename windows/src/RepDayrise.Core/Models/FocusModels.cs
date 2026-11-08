using System.Text.Json.Serialization;

namespace RepDayrise.Core.Models;

public enum FocusPhase { FOCUS, SHORT_BREAK, LONG_BREAK }

/// <summary>How a recorded focus session ended.</summary>
public enum FocusSessionStatus
{
    /// <summary>Ran its full planned length.</summary>
    COMPLETED,
    /// <summary>Stopped early; recorded because enough time was focused.</summary>
    STOPPED,
    /// <summary>Skipped to the next phase early; recorded because enough time was focused.</summary>
    SKIPPED,
}

/// <summary>Settings for one run of focus sessions, captured when the run starts so later edits don't disturb it.</summary>
public sealed record FocusConfig
{
    public long FocusMs { get; init; } = 25 * 60_000;
    public long ShortBreakMs { get; init; } = 5 * 60_000;
    public long LongBreakMs { get; init; } = 15 * 60_000;
    /// <summary>A long break follows every Nth focus phase.</summary>
    public int LongBreakEvery { get; init; } = 4;
    public bool BreaksEnabled { get; init; } = true;
    public bool AutoStartBreaks { get; init; } = true;
    public bool AutoStartFocus { get; init; }
    /// <summary>Stopped or skipped focus counts only if at least this long.</summary>
    public long MinCreditMs { get; init; } = 60_000;
    /// <summary>Whether stopped or skipped focus is recorded at all.</summary>
    public bool CountPartial { get; init; } = true;

    public long DurationOf(FocusPhase phase) => phase switch
    {
        FocusPhase.FOCUS => FocusMs,
        FocusPhase.SHORT_BREAK => ShortBreakMs,
        _ => LongBreakMs,
    };
}

/// <summary>
/// The focus run in progress on this device. Everything is derived from timestamps, so nothing depends on a timer
/// ticking; the app can close and reopen and the run carries on. Not synced between devices.
/// </summary>
public sealed record FocusRun
{
    public string RunUid { get; init; } = Guid.NewGuid().ToString();
    public required FocusConfig Config { get; init; }
    public long? HabitId { get; init; }
    public FocusPhase Phase { get; init; } = FocusPhase.FOCUS;
    /// <summary>Focus phases completed so far in this run (decides when the long break comes).</summary>
    public int CompletedFocusCount { get; init; }
    /// <summary>The uid the current focus phase will be recorded under, so recording is idempotent.</summary>
    public string PhaseUid { get; init; } = Guid.NewGuid().ToString();
    /// <summary>The local date the current phase started on; its minutes count toward this day.</summary>
    public DateOnly PhaseDate { get; init; }
    /// <summary>When the phase first started (ms), for the session record.</summary>
    public long PhaseFirstStartedAt { get; init; }
    /// <summary>When the phase last resumed (ms); meaningful while <see cref="Running"/>.</summary>
    public long PhaseStartedAt { get; init; }
    public long PhaseDurationMs { get; init; }
    /// <summary>Time already run before the last resume.</summary>
    public long AccumulatedMs { get; init; }
    public bool Running { get; init; }
    /// <summary>The phase is set up but waits for the user to start it (auto-start is off, or nobody was there).</summary>
    public bool AwaitingStart { get; init; }
    /// <summary>An extra focus phase added after one finished ("+5 min"); it doesn't count toward the long break.</summary>
    public bool Bonus { get; init; }

    public long ElapsedMs(long now) => AccumulatedMs + (Running ? Math.Max(now - PhaseStartedAt, 0) : 0);

    public long RemainingMs(long now) => Math.Max(PhaseDurationMs - ElapsedMs(now), 0);

    /// <summary>0..1 through the current phase.</summary>
    public double Fraction(long now) => PhaseDurationMs <= 0 ? 1 : Math.Clamp(ElapsedMs(now) / (double)PhaseDurationMs, 0, 1);

    /// <summary>When the running phase will end (ms), or null while paused or waiting.</summary>
    [JsonIgnore]
    public long? PhaseEndsAt => Running ? PhaseStartedAt + (PhaseDurationMs - AccumulatedMs) : null;

    [JsonIgnore]
    public bool IsBreak => Phase != FocusPhase.FOCUS;
}

/// <summary>A finished focus phase. Synced between devices in future, so it carries a uid and soft-delete fields.</summary>
public sealed record FocusSession
{
    public long Id { get; init; }
    public string Uid { get; init; } = Guid.NewGuid().ToString();
    public long? HabitId { get; init; }
    /// <summary>The habit's uid at recording time, so a sync merge can match habits across devices.</summary>
    public string? HabitUid { get; init; }
    public DateOnly Date { get; init; }
    public long StartedAt { get; init; }
    public long EndedAt { get; init; }
    public long PlannedMs { get; init; }
    public long FocusedMs { get; init; }
    public FocusSessionStatus Status { get; init; }
    /// <summary>Minutes added to the habit's entry for <see cref="Date"/>; 0 when untagged or not a timer habit.</summary>
    public double CreditedMinutes { get; init; }
    public string Note { get; init; } = "";

    public double FocusedMinutes => FocusedMs / 60000.0;
}
