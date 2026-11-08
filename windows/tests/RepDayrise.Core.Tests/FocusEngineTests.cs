using RepDayrise.Core.Domain;
using RepDayrise.Core.Models;

namespace RepDayrise.Core.Tests;

public class FocusEngineTests
{
    private const long Min = 60_000;
    private static readonly DateOnly Day = new(2026, 11, 8);
    private static readonly long T0 = new DateTimeOffset(2026, 11, 8, 9, 0, 0, TimeSpan.Zero).ToUnixTimeMilliseconds();

    /// <summary>UTC dates keep the tests independent of the machine's time zone.</summary>
    private static DateOnly DateOf(long ms) => DateOnly.FromDateTime(DateTimeOffset.FromUnixTimeMilliseconds(ms).UtcDateTime);

    private static readonly FocusConfig Pomodoro = new()
    {
        FocusMs = 25 * Min, ShortBreakMs = 5 * Min, LongBreakMs = 15 * Min, LongBreakEvery = 4,
        BreaksEnabled = true, AutoStartBreaks = true, AutoStartFocus = true,
    };

    private static FocusTransition Do(FocusRun? run, FocusCommand c, long now) => FocusEngine.Apply(run, c, now, DateOf);

    private static FocusRun Started(FocusConfig? config = null, long? habitId = 3) =>
        Do(null, new FocusCommand.Start(config ?? Pomodoro, habitId), T0).Next!;

    private static T Single<T>(FocusTransition t) => t.Effects.OfType<T>().Single();

    [Fact]
    public void StartBeginsARunningFocusPhaseAndSchedulesItsEnd()
    {
        var t = Do(null, new FocusCommand.Start(Pomodoro, 3), T0);
        var run = t.Next!;
        Assert.Equal(FocusPhase.FOCUS, run.Phase);
        Assert.True(run.Running);
        Assert.Equal(Day, run.PhaseDate);
        Assert.Equal(T0 + 25 * Min, run.PhaseEndsAt);
        var alarm = Single<FocusEffect.ScheduleAlarm>(t).Alarm;
        Assert.Equal(T0 + 25 * Min, alarm.At);
        Assert.Equal(FocusPhase.SHORT_BREAK, alarm.Next);
        Assert.True(alarm.NextStartsByItself);
    }

    [Fact]
    public void TickBeforeTheEndChangesNothing()
    {
        var run = Started();
        var t = Do(run, new FocusCommand.Tick(), T0 + 10 * Min);
        Assert.Same(run, t.Next);
        Assert.Empty(t.Effects);
    }

    [Fact]
    public void FocusCompletesAtItsPlannedEndAndAShortBreakStarts()
    {
        var run = Started();
        var end = T0 + 25 * Min;
        var t = Do(run, new FocusCommand.Tick(), end + 2_000);

        var s = Single<FocusEffect.RecordSession>(t).Session;
        Assert.Equal(FocusSessionStatus.COMPLETED, s.Status);
        Assert.Equal(end, s.EndedAt);
        Assert.Equal(25 * Min, s.FocusedMs);
        Assert.Equal(3L, s.HabitId);
        Assert.Equal(run.PhaseUid, s.Uid);

        var brk = t.Next!;
        Assert.Equal(FocusPhase.SHORT_BREAK, brk.Phase);
        Assert.True(brk.Running);
        Assert.Equal(end, brk.PhaseStartedAt); // starts when focus ended, not when we noticed
        Assert.Equal(1, brk.CompletedFocusCount);
        Assert.Equal(new FocusEffect.PhaseCompleted(FocusPhase.FOCUS, FocusPhase.SHORT_BREAK, true), Single<FocusEffect.PhaseCompleted>(t));
    }

    [Fact]
    public void EveryFourthFocusIsFollowedByALongBreak()
    {
        var run = Started();
        var now = T0;
        var phases = new List<FocusPhase>();
        for (var i = 0; i < 8; i++)
        {
            now = run.PhaseEndsAt!.Value;
            run = Do(run, new FocusCommand.Tick(), now).Next!;
            phases.Add(run.Phase);
        }
        Assert.Equal(
        [
            FocusPhase.SHORT_BREAK, FocusPhase.FOCUS, FocusPhase.SHORT_BREAK, FocusPhase.FOCUS,
            FocusPhase.SHORT_BREAK, FocusPhase.FOCUS, FocusPhase.LONG_BREAK, FocusPhase.FOCUS,
        ], phases);
    }

    [Fact]
    public void WithBreaksOffTheRunEndsAfterOneFocus()
    {
        var run = Started(Pomodoro with { BreaksEnabled = false, FocusMs = 90 * Min });
        var t = Do(run, new FocusCommand.Tick(), T0 + 90 * Min);
        Assert.Null(t.Next);
        Assert.Equal(90 * Min, Single<FocusEffect.RecordSession>(t).Session.FocusedMs);
        Assert.Equal(new FocusEffect.PhaseCompleted(FocusPhase.FOCUS, null, false), Single<FocusEffect.PhaseCompleted>(t));
        Assert.Null(FocusEngine.AlarmFor(run).Next);
    }

    [Fact]
    public void WithoutAutoStartTheNextPhaseWaits()
    {
        var run = Started(Pomodoro with { AutoStartBreaks = false });
        var t = Do(run, new FocusCommand.Tick(), T0 + 25 * Min);
        Assert.True(t.Next!.AwaitingStart);
        Assert.False(t.Next.Running);
        Assert.Equal(FocusPhase.SHORT_BREAK, t.Next.Phase);
        Assert.Single(t.Effects.OfType<FocusEffect.CancelAlarm>());

        var resumed = Do(t.Next, new FocusCommand.Resume(), T0 + 30 * Min).Next!;
        Assert.True(resumed.Running);
        Assert.Equal(T0 + 35 * Min, resumed.PhaseEndsAt);
    }

    [Fact]
    public void PauseAndResumeKeepTheRemainingTime()
    {
        var run = Started();
        var paused = Do(run, new FocusCommand.Pause(), T0 + 10 * Min);
        Assert.Single(paused.Effects.OfType<FocusEffect.CancelAlarm>());
        Assert.Equal(15 * Min, paused.Next!.RemainingMs(T0 + 60 * Min)); // time stands still while paused
        Assert.Same(paused.Next, Do(paused.Next, new FocusCommand.Tick(), T0 + 60 * Min).Next);

        var resumed = Do(paused.Next, new FocusCommand.Resume(), T0 + 60 * Min);
        Assert.Equal(T0 + 75 * Min, resumed.Next!.PhaseEndsAt);
        Assert.Equal(T0 + 75 * Min, Single<FocusEffect.ScheduleAlarm>(resumed).Alarm.At);

        var done = Do(resumed.Next, new FocusCommand.Tick(), T0 + 75 * Min);
        var s = Single<FocusEffect.RecordSession>(done).Session;
        Assert.Equal(T0, s.StartedAt);
        Assert.Equal(25 * Min, s.FocusedMs);
    }

    [Fact]
    public void ExtendLengthensTheFocusPhase()
    {
        var run = Started();
        var t = Do(run, new FocusCommand.Extend(5 * Min), T0 + 20 * Min);
        Assert.Equal(T0 + 30 * Min, t.Next!.PhaseEndsAt);
        Assert.Equal(T0 + 30 * Min, Single<FocusEffect.ScheduleAlarm>(t).Alarm.At);
    }

    [Fact]
    public void ExtendAfterFocusEndsAddsABonusPhaseThatDoesNotCountTowardTheLongBreak()
    {
        var run = Started();
        var inBreak = Do(run, new FocusCommand.Tick(), T0 + 25 * Min).Next!;
        var bonus = Do(inBreak, new FocusCommand.Extend(5 * Min), T0 + 26 * Min).Next!;
        Assert.Equal(FocusPhase.FOCUS, bonus.Phase);
        Assert.True(bonus.Bonus);
        Assert.NotEqual(run.PhaseUid, bonus.PhaseUid);

        var after = Do(bonus, new FocusCommand.Tick(), T0 + 31 * Min);
        Assert.Equal(5 * Min, Single<FocusEffect.RecordSession>(after).Session.FocusedMs);
        Assert.Equal(1, after.Next!.CompletedFocusCount);
        Assert.Equal(FocusPhase.SHORT_BREAK, after.Next.Phase);
    }

    [Fact]
    public void SkippingFocusRecordsWhatWasDoneAndStartsTheBreakNow()
    {
        var run = Started();
        var t = Do(run, new FocusCommand.Skip(), T0 + 12 * Min);
        var s = Single<FocusEffect.RecordSession>(t).Session;
        Assert.Equal(FocusSessionStatus.SKIPPED, s.Status);
        Assert.Equal(12 * Min, s.FocusedMs);
        Assert.Equal(FocusPhase.SHORT_BREAK, t.Next!.Phase);
        Assert.Equal(T0 + 12 * Min, t.Next.PhaseStartedAt);
        Assert.Equal(1, t.Next.CompletedFocusCount);
    }

    [Fact]
    public void ShortFocusIsNotRecordedWhenSkippedOrStopped()
    {
        var run = Started();
        var skipped = Do(run, new FocusCommand.Skip(), T0 + 30_000);
        Assert.Empty(skipped.Effects.OfType<FocusEffect.RecordSession>());
        Assert.Equal(0, skipped.Next!.CompletedFocusCount);

        var stopped = Do(run, new FocusCommand.Stop(), T0 + 30_000);
        Assert.Null(stopped.Next);
        Assert.Empty(stopped.Effects.OfType<FocusEffect.RecordSession>());
    }

    [Fact]
    public void PartialFocusIsNotRecordedWhenCountPartialIsOff()
    {
        var run = Started(Pomodoro with { CountPartial = false });
        Assert.Empty(Do(run, new FocusCommand.Stop(), T0 + 20 * Min).Effects.OfType<FocusEffect.RecordSession>());
    }

    [Fact]
    public void StopRecordsPartialFocusAndEndsTheRun()
    {
        var run = Started();
        var t = Do(run, new FocusCommand.Stop(), T0 + 7 * Min);
        Assert.Null(t.Next);
        Assert.Equal(FocusSessionStatus.STOPPED, Single<FocusEffect.RecordSession>(t).Session.Status);
        Assert.Single(t.Effects.OfType<FocusEffect.CancelAlarm>());
    }

    [Fact]
    public void StoppingDuringABreakRecordsNothing()
    {
        var inBreak = Do(Started(), new FocusCommand.Tick(), T0 + 25 * Min).Next!;
        var t = Do(inBreak, new FocusCommand.Stop(), T0 + 27 * Min);
        Assert.Null(t.Next);
        Assert.Empty(t.Effects.OfType<FocusEffect.RecordSession>());
    }

    [Fact]
    public void SkippingABreakStartsFocusNow()
    {
        var inBreak = Do(Started(), new FocusCommand.Tick(), T0 + 25 * Min).Next!;
        var t = Do(inBreak, new FocusCommand.Skip(), T0 + 26 * Min);
        Assert.Equal(FocusPhase.FOCUS, t.Next!.Phase);
        Assert.True(t.Next.Running);
        Assert.Equal(T0 + 51 * Min, t.Next.PhaseEndsAt);
    }

    [Fact]
    public void ComingBackLateCompletesOnePhaseAndWaitsInsteadOfChaining()
    {
        var run = Started();
        // The app was closed for two hours: focus finished long ago, and nobody was there for the break.
        var t = Do(run, new FocusCommand.Tick(), T0 + 145 * Min);
        Assert.Single(t.Effects.OfType<FocusEffect.RecordSession>());
        Assert.Equal(T0 + 25 * Min, Single<FocusEffect.RecordSession>(t).Session.EndedAt);
        Assert.True(t.Next!.AwaitingStart);
        Assert.Equal(FocusPhase.SHORT_BREAK, t.Next.Phase);
        Assert.Same(t.Next, Do(t.Next, new FocusCommand.Tick(), T0 + 300 * Min).Next);
    }

    [Fact]
    public void AClockThatWentBackwardsNeverGivesNegativeTime()
    {
        var run = Started();
        Assert.Equal(0, run.ElapsedMs(T0 - 10 * Min));
        Assert.Equal(25 * Min, run.RemainingMs(T0 - 10 * Min));
        var stopped = Do(run, new FocusCommand.Stop(), T0 - 10 * Min);
        Assert.Empty(stopped.Effects.OfType<FocusEffect.RecordSession>());
    }

    [Fact]
    public void FocusAcrossMidnightCountsForTheDayItStarted()
    {
        var late = new DateTimeOffset(2026, 11, 8, 23, 50, 0, TimeSpan.Zero).ToUnixTimeMilliseconds();
        var run = Do(null, new FocusCommand.Start(Pomodoro, 3), late).Next!;
        var t = Do(run, new FocusCommand.Tick(), late + 25 * Min);
        Assert.Equal(Day, Single<FocusEffect.RecordSession>(t).Session.Date);
        Assert.Equal(Day.AddDays(1), t.Next!.PhaseDate); // the break belongs to the new day
    }

    [Fact]
    public void StartingAgainStopsTheRunInProgress()
    {
        var run = Started();
        var t = Do(run, new FocusCommand.Start(Pomodoro, null), T0 + 10 * Min);
        Assert.Equal(FocusSessionStatus.STOPPED, Single<FocusEffect.RecordSession>(t).Session.Status);
        Assert.Null(t.Next!.HabitId);
        Assert.NotEqual(run.RunUid, t.Next.RunUid);
    }
}
