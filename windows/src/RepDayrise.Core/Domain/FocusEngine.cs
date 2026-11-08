using RepDayrise.Core.Models;

namespace RepDayrise.Core.Domain;

/// <summary>Something the user (or time) does to a focus run.</summary>
public abstract record FocusCommand
{
    private FocusCommand() { }

    /// <summary>Begin a new run with a focus phase. Ends any run in progress first.</summary>
    public sealed record Start(FocusConfig Config, long? HabitId) : FocusCommand;
    public sealed record Pause : FocusCommand;
    /// <summary>Resume a paused phase, or start one that is waiting.</summary>
    public sealed record Resume : FocusCommand;
    /// <summary>Jump to the next phase and start it now.</summary>
    public sealed record Skip : FocusCommand;
    /// <summary>End the run.</summary>
    public sealed record Stop : FocusCommand;
    /// <summary>Lengthen the focus phase, or (after one has finished) add a bonus focus phase of this length.</summary>
    public sealed record Extend(long Ms) : FocusCommand;
    /// <summary>Time passed: complete the current phase if it's due. Also used when the app starts.</summary>
    public sealed record Tick : FocusCommand;
}

/// <summary>A phase-end notification to schedule.</summary>
/// <param name="At">When the phase ends (Unix ms).</param>
/// <param name="Ending">The phase that ends.</param>
/// <param name="Next">What comes after it, or null if the run ends.</param>
/// <param name="NextStartsByItself">Whether <paramref name="Next"/> will start without the user.</param>
/// <param name="NextMs">Length of the next phase.</param>
public sealed record FocusAlarm(long At, FocusPhase Ending, FocusPhase? Next, bool NextStartsByItself, long NextMs);

/// <summary>Side effects for the caller to carry out, in order.</summary>
public abstract record FocusEffect
{
    private FocusEffect() { }

    public sealed record RecordSession(FocusSession Session) : FocusEffect;
    public sealed record ScheduleAlarm(FocusAlarm Alarm) : FocusEffect;
    public sealed record CancelAlarm : FocusEffect;
    /// <summary>A phase ran to its end (not skipped or stopped). <paramref name="Next"/> is null when the run ends.</summary>
    public sealed record PhaseCompleted(FocusPhase Ended, FocusPhase? Next, bool NextStarted) : FocusEffect;
}

public sealed record FocusTransition(FocusRun? Next, IReadOnlyList<FocusEffect> Effects);

/// <summary>
/// The focus session state machine. Pure: it takes the run, a command and the time, and returns the next run plus
/// effects (sessions to record, alarms to set). It never chains phases while nobody is there: a phase that would
/// start by itself more than <see cref="LiveWindowMs"/> after it was due waits for the user instead, so closing the
/// app or sleeping the PC can't invent focus time.
/// </summary>
public static class FocusEngine
{
    /// <summary>How late a phase end may be noticed and still auto-start the next phase.</summary>
    public const long LiveWindowMs = 60_000;

    /// <param name="dateOf">Maps a Unix ms time to the local date (the day a phase's minutes count toward).</param>
    public static FocusTransition Apply(FocusRun? run, FocusCommand command, long now, Func<long, DateOnly> dateOf)
    {
        var effects = new List<FocusEffect>();
        var next = command switch
        {
            FocusCommand.Start s => Start(run, s, now, dateOf, effects),
            FocusCommand.Pause => Pause(run, now, effects),
            FocusCommand.Resume => Resume(run, now, dateOf, effects),
            FocusCommand.Skip => Skip(run, now, dateOf, effects),
            FocusCommand.Stop => Stop(run, now, effects),
            FocusCommand.Extend e => Extend(run, e.Ms, now, dateOf, effects),
            FocusCommand.Tick => Tick(run, now, dateOf, effects),
            _ => run,
        };
        return new FocusTransition(next, effects);
    }

    private static FocusRun Start(FocusRun? run, FocusCommand.Start s, long now, Func<long, DateOnly> dateOf, List<FocusEffect> fx)
    {
        if (run != null) RecordPartial(run, now, FocusSessionStatus.STOPPED, fx);
        var started = new FocusRun
        {
            Config = s.Config,
            HabitId = s.HabitId,
            Phase = FocusPhase.FOCUS,
            PhaseDurationMs = s.Config.FocusMs,
        };
        return Begin(started, now, dateOf, fx);
    }

    private static FocusRun? Pause(FocusRun? run, long now, List<FocusEffect> fx)
    {
        if (run is not { Running: true }) return run;
        fx.Add(new FocusEffect.CancelAlarm());
        return run with { AccumulatedMs = run.ElapsedMs(now), Running = false };
    }

    private static FocusRun? Resume(FocusRun? run, long now, Func<long, DateOnly> dateOf, List<FocusEffect> fx)
    {
        if (run == null || run.Running) return run;
        if (run.AwaitingStart) return Begin(run with { AwaitingStart = false }, now, dateOf, fx);
        var resumed = run with { PhaseStartedAt = now, Running = true };
        fx.Add(new FocusEffect.ScheduleAlarm(AlarmFor(resumed)));
        return resumed;
    }

    private static FocusRun? Skip(FocusRun? run, long now, Func<long, DateOnly> dateOf, List<FocusEffect> fx)
    {
        if (run == null) return null;
        if (run.AwaitingStart)
        {
            // Skipping a phase nobody started: a waiting break goes straight to focus; a waiting focus just starts.
            var target = run.IsBreak ? Phase(run, FocusPhase.FOCUS) : run;
            return Begin(target with { AwaitingStart = false }, now, dateOf, fx);
        }
        if (run.IsBreak) return Begin(Phase(run, FocusPhase.FOCUS), now, dateOf, fx);

        var recorded = RecordPartial(run, now, FocusSessionStatus.SKIPPED, fx);
        if (!run.Config.BreaksEnabled)
        {
            fx.Add(new FocusEffect.CancelAlarm());
            return null;
        }
        // Only focus that counted moves the long-break counter; otherwise take a short break.
        if (!recorded || run.Bonus) return Begin(Phase(run, FocusPhase.SHORT_BREAK), now, dateOf, fx);
        var afterSkip = run with { CompletedFocusCount = run.CompletedFocusCount + 1 };
        return Begin(Phase(afterSkip, BreakAfter(afterSkip)), now, dateOf, fx);
    }

    private static FocusRun? Stop(FocusRun? run, long now, List<FocusEffect> fx)
    {
        if (run == null) return null;
        RecordPartial(run, now, FocusSessionStatus.STOPPED, fx);
        fx.Add(new FocusEffect.CancelAlarm());
        return null;
    }

    private static FocusRun? Extend(FocusRun? run, long ms, long now, Func<long, DateOnly> dateOf, List<FocusEffect> fx)
    {
        if (run == null || ms <= 0) return run;
        if (run is { Phase: FocusPhase.FOCUS, AwaitingStart: false })
        {
            var longer = run with { PhaseDurationMs = run.PhaseDurationMs + ms };
            if (longer.Running) fx.Add(new FocusEffect.ScheduleAlarm(AlarmFor(longer)));
            return longer;
        }
        // A focus phase just finished (we're in or waiting for a break, or waiting for the next focus):
        // keep going with a short bonus focus phase.
        var bonus = run with
        {
            Phase = FocusPhase.FOCUS,
            PhaseDurationMs = ms,
            PhaseUid = Guid.NewGuid().ToString(),
            AccumulatedMs = 0,
            AwaitingStart = false,
            Bonus = true,
        };
        return Begin(bonus, now, dateOf, fx);
    }

    private static FocusRun? Tick(FocusRun? run, long now, Func<long, DateOnly> dateOf, List<FocusEffect> fx)
    {
        if (run is not { Running: true } || run.PhaseEndsAt is not long end || now < end) return run;

        // The phase ran to its end. Record it as of its planned end, not as of when we noticed.
        FocusRun after;
        if (run.Phase == FocusPhase.FOCUS)
        {
            fx.Add(new FocusEffect.RecordSession(SessionOf(run, end, run.PhaseDurationMs, FocusSessionStatus.COMPLETED)));
            var count = run.Bonus ? run.CompletedFocusCount : run.CompletedFocusCount + 1;
            if (!run.Config.BreaksEnabled)
            {
                fx.Add(new FocusEffect.PhaseCompleted(FocusPhase.FOCUS, null, false));
                fx.Add(new FocusEffect.CancelAlarm());
                return null;
            }
            after = run with { CompletedFocusCount = count };
            after = Phase(after, BreakAfter(after));
        }
        else
        {
            after = Phase(run, FocusPhase.FOCUS);
        }

        var auto = (after.IsBreak ? run.Config.AutoStartBreaks : run.Config.AutoStartFocus) && now - end <= LiveWindowMs;
        fx.Add(new FocusEffect.PhaseCompleted(run.Phase, after.Phase, auto));
        if (auto) return Begin(after, end, dateOf, fx);
        fx.Add(new FocusEffect.CancelAlarm());
        return after with { AwaitingStart = true, Running = false, PhaseDate = dateOf(now) };
    }

    // ---- Helpers ------------------------------------------------------------------------------

    /// <summary>A fresh, not-yet-started copy of the run in <paramref name="phase"/>.</summary>
    private static FocusRun Phase(FocusRun run, FocusPhase phase) => run with
    {
        Phase = phase,
        PhaseDurationMs = run.Config.DurationOf(phase),
        PhaseUid = Guid.NewGuid().ToString(),
        AccumulatedMs = 0,
        Running = false,
        Bonus = false,
    };

    /// <summary>Starts the run's current phase at <paramref name="at"/> and schedules its alarm.</summary>
    private static FocusRun Begin(FocusRun run, long at, Func<long, DateOnly> dateOf, List<FocusEffect> fx)
    {
        var started = run with
        {
            PhaseDate = dateOf(at),
            PhaseFirstStartedAt = at,
            PhaseStartedAt = at,
            AccumulatedMs = 0,
            Running = true,
            AwaitingStart = false,
        };
        fx.Add(new FocusEffect.ScheduleAlarm(AlarmFor(started)));
        return started;
    }

    private static FocusPhase BreakAfter(FocusRun run) =>
        run.CompletedFocusCount > 0 && run.CompletedFocusCount % run.Config.LongBreakEvery == 0 ? FocusPhase.LONG_BREAK : FocusPhase.SHORT_BREAK;

    /// <summary>What the phase-end notification should say, assuming someone is there when it fires.</summary>
    internal static FocusAlarm AlarmFor(FocusRun run)
    {
        var at = run.PhaseEndsAt ?? 0;
        if (run.Phase == FocusPhase.FOCUS)
        {
            if (!run.Config.BreaksEnabled) return new FocusAlarm(at, FocusPhase.FOCUS, null, false, 0);
            var after = run with { CompletedFocusCount = run.Bonus ? run.CompletedFocusCount : run.CompletedFocusCount + 1 };
            var next = BreakAfter(after);
            return new FocusAlarm(at, FocusPhase.FOCUS, next, run.Config.AutoStartBreaks, run.Config.DurationOf(next));
        }
        return new FocusAlarm(at, run.Phase, FocusPhase.FOCUS, run.Config.AutoStartFocus, run.Config.FocusMs);
    }

    /// <summary>Records the focus done so far in a phase cut short, if it's long enough to count. Breaks record nothing.</summary>
    private static bool RecordPartial(FocusRun run, long now, FocusSessionStatus status, List<FocusEffect> fx)
    {
        if (run.Phase != FocusPhase.FOCUS || run.AwaitingStart || !run.Config.CountPartial) return false;
        var focused = Math.Min(run.ElapsedMs(now), run.PhaseDurationMs);
        if (focused < run.Config.MinCreditMs) return false;
        fx.Add(new FocusEffect.RecordSession(SessionOf(run, now, focused, status)));
        return true;
    }

    private static FocusSession SessionOf(FocusRun run, long endedAt, long focusedMs, FocusSessionStatus status) => new()
    {
        Uid = run.PhaseUid,
        HabitId = run.HabitId,
        Date = run.PhaseDate,
        StartedAt = run.PhaseFirstStartedAt,
        EndedAt = endedAt,
        PlannedMs = run.PhaseDurationMs,
        FocusedMs = focusedMs,
        Status = status,
    };
}
