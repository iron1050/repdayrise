using RepDayrise.Core.Data;
using RepDayrise.Core.Domain;
using RepDayrise.Core.Models;

namespace RepDayrise.Core.Focus;

/// <summary>Schedules the phase-end notification so it fires even when the app is closed.</summary>
public interface IFocusAlarm
{
    void Schedule(FocusAlarm alarm);
    void Cancel();
}

/// <summary>
/// Runs focus sessions: loads the run from the store, applies <see cref="FocusEngine"/>, and carries out its effects
/// in a fixed order (record sessions, save the run, set the alarm, raise events). The run lives in SQLite, so the app
/// can close mid-session, and a future widget process sharing the database sees the same session.
/// </summary>
public sealed class FocusService(HabitStore store, IFocusAlarm alarm, Func<long>? clock = null, Func<long, DateOnly>? dateOf = null)
{
    private readonly Func<long> _clock = clock ?? Clock.NowMs;
    private readonly Func<long, DateOnly> _dateOf = dateOf ?? LocalDate;
    private readonly Lock _lock = new();

    /// <summary>Raised after every change to the run, on the calling thread.</summary>
    public event Action<FocusRun?>? Changed;

    /// <summary>Raised when a phase runs to its end (not when skipped or stopped), for chimes and celebrations.</summary>
    public event Action<FocusEffect.PhaseCompleted>? PhaseCompleted;

    /// <summary>Raised when a session is recorded.</summary>
    public event Action<FocusSession>? SessionRecorded;

    public FocusRun? Current => store.GetFocusRun();

    public long Now => _clock();

    /// <summary>
    /// Starts a run. If the habit has a manual timer running, that timer is finished first so its minutes are
    /// committed and nothing is counted twice.
    /// </summary>
    public FocusRun? Start(FocusConfig config, long? habitId)
    {
        if (habitId is long id && store.GetTimer(id) != null && store.GetHabit(id) is { } habit) store.FinishTimer(habit);
        return Apply(new FocusCommand.Start(config, habitId));
    }

    public FocusRun? Pause() => Apply(new FocusCommand.Pause());
    public FocusRun? Resume() => Apply(new FocusCommand.Resume());
    public FocusRun? Skip() => Apply(new FocusCommand.Skip());
    public FocusRun? Stop() => Apply(new FocusCommand.Stop());
    public FocusRun? Extend(TimeSpan by) => Apply(new FocusCommand.Extend((long)by.TotalMilliseconds));

    /// <summary>Completes the current phase if it's due. Call every second while visible, and once at startup.</summary>
    public FocusRun? Tick() => Apply(new FocusCommand.Tick());

    /// <summary>Pause if running, otherwise resume or start the waiting phase.</summary>
    public FocusRun? Toggle()
    {
        var run = Current;
        return run is { Running: true } ? Pause() : Resume();
    }

    /// <summary>Points the run at a different habit (or none). Only affects sessions recorded from now on.</summary>
    public FocusRun? SetHabit(long? habitId)
    {
        lock (_lock)
        {
            var run = store.GetFocusRun();
            if (run == null) return null;
            run = run with { HabitId = habitId };
            store.SaveFocusRun(run);
            Changed?.Invoke(run);
            return run;
        }
    }

    private FocusRun? Apply(FocusCommand command)
    {
        FocusTransition t;
        var recorded = new List<FocusSession>();
        lock (_lock)
        {
            var before = store.GetFocusRun();
            t = FocusEngine.Apply(before, command, _clock(), _dateOf);
            if (ReferenceEquals(before, t.Next) || (t.Effects.Count == 0 && Equals(before, t.Next))) return before;

            foreach (var e in t.Effects.OfType<FocusEffect.RecordSession>())
                if (store.RecordFocusSession(e.Session) is { } s) recorded.Add(s);

            if (t.Next == null) store.ClearFocusRun(); else store.SaveFocusRun(t.Next);

            foreach (var e in t.Effects)
            {
                if (e is FocusEffect.ScheduleAlarm s) alarm.Schedule(s.Alarm);
                else if (e is FocusEffect.CancelAlarm) alarm.Cancel();
            }
        }
        foreach (var s in recorded) SessionRecorded?.Invoke(s);
        foreach (var e in t.Effects.OfType<FocusEffect.PhaseCompleted>()) PhaseCompleted?.Invoke(e);
        Changed?.Invoke(t.Next);
        return t.Next;
    }

    private static DateOnly LocalDate(long ms) => DateOnly.FromDateTime(DateTimeOffset.FromUnixTimeMilliseconds(ms).LocalDateTime);
}
