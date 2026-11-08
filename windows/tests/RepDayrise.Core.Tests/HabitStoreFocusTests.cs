using Microsoft.Data.Sqlite;
using RepDayrise.Core.Data;
using RepDayrise.Core.Models;

namespace RepDayrise.Core.Tests;

public sealed class HabitStoreFocusTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "dayrise-tests-" + Guid.NewGuid().ToString("N"));
    private readonly string _path;
    private readonly HabitStore _store;
    private static readonly DateOnly Today = new(2026, 11, 8);

    public HabitStoreFocusTests()
    {
        _path = Path.Combine(_dir, "dayrise.db");
        _store = new HabitStore(_path);
    }

    public void Dispose()
    {
        SqliteConnection.ClearAllPools();
        try { Directory.Delete(_dir, recursive: true); } catch { /* best effort */ }
    }

    private object? Raw(string sql)
    {
        using var c = new SqliteConnection($"Data Source={_path}");
        c.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = sql;
        return cmd.ExecuteScalar();
    }

    private long TimerHabit(string name = "Deep work") =>
        _store.SaveHabit(new Habit { Name = name, Type = HabitType.TIMER, Goal = 120, StartDate = Today });

    private static FocusSession Session(long? habitId, double minutes, string? uid = null) => new()
    {
        Uid = uid ?? Guid.NewGuid().ToString(),
        HabitId = habitId,
        Date = Today,
        StartedAt = 1_000,
        EndedAt = 1_000 + (long)(minutes * 60_000),
        PlannedMs = 25 * 60_000,
        FocusedMs = (long)(minutes * 60_000),
        Status = FocusSessionStatus.COMPLETED,
    };

    [Fact]
    public void FreshDatabaseIsAtSchemaV2()
    {
        Assert.Equal(2L, Raw("PRAGMA user_version"));
        Assert.Equal(0L, Raw("SELECT COUNT(*) FROM focus_sessions"));
    }

    [Fact]
    public void MigratesAV1DatabaseKeepingItsData()
    {
        var id = TimerHabit();
        _store.SetValue(id, Today, 30);
        // Turn this into a v1 database: no focus tables, version 1.
        Raw("DROP TABLE focus_sessions; DROP TABLE focus_run; PRAGMA user_version = 1;");
        SqliteConnection.ClearAllPools();

        var reopened = new HabitStore(_path);
        Assert.Equal(2L, Raw("PRAGMA user_version"));
        Assert.Equal(30, reopened.CurrentValue(id, Today));
        Assert.NotNull(reopened.RecordFocusSession(Session(id, 25)));
    }

    [Fact]
    public void CreditsATimerHabitOnce()
    {
        var id = TimerHabit();
        _store.SetValue(id, Today, 10);
        var s = Session(id, 25);

        var stored = _store.RecordFocusSession(s);
        Assert.NotNull(stored);
        Assert.Equal(25, stored!.CreditedMinutes, 6);
        Assert.Equal(35, _store.CurrentValue(id, Today), 6);

        Assert.Null(_store.RecordFocusSession(s)); // replaying after a crash must not double count
        Assert.Equal(35, _store.CurrentValue(id, Today), 6);
        Assert.Single(_store.GetFocusSessions(Today, Today));
    }

    [Fact]
    public void DoesNotCreditUntaggedOrNonTimerHabits()
    {
        var check = _store.SaveHabit(new Habit { Name = "Stretch", StartDate = Today });
        Assert.Equal(0, _store.RecordFocusSession(Session(check, 25))!.CreditedMinutes);
        Assert.Equal(0, _store.CurrentValue(check, Today));

        var untagged = _store.RecordFocusSession(Session(null, 25))!;
        Assert.Null(untagged.HabitId);
        Assert.Equal(0, untagged.CreditedMinutes);
        Assert.Equal(2, _store.GetFocusSessions(Today, Today).Count);
    }

    [Fact]
    public void RecordsTheHabitUid()
    {
        var id = TimerHabit();
        var stored = _store.RecordFocusSession(Session(id, 25))!;
        Assert.Equal(_store.GetHabit(id)!.Uid, stored.HabitUid);
    }

    [Fact]
    public void DeletingASessionTakesItsCreditBackAndClampsAtZero()
    {
        var id = TimerHabit();
        var s = _store.RecordFocusSession(Session(id, 25))!;
        _store.SetValue(id, Today, 10); // the user lowered the value by hand since
        _store.DeleteFocusSession(s.Uid);

        Assert.Equal(0, _store.CurrentValue(id, Today));
        Assert.Empty(_store.GetFocusSessions(Today, Today));
        Assert.NotNull(Raw($"SELECT deletedAt FROM focus_sessions WHERE uid = '{s.Uid}'"));
    }

    [Fact]
    public void RetaggingMovesTheCredit()
    {
        var a = TimerHabit("A");
        var b = TimerHabit("B");
        var s = _store.RecordFocusSession(Session(a, 30))!;

        _store.RetagFocusSession(s.Uid, b);
        Assert.Equal(0, _store.CurrentValue(a, Today), 6);
        Assert.Equal(30, _store.CurrentValue(b, Today), 6);

        _store.RetagFocusSession(s.Uid, null);
        Assert.Equal(0, _store.CurrentValue(b, Today), 6);
        Assert.Null(_store.GetFocusSessions(Today, Today).Single().HabitId);
    }

    [Fact]
    public void SessionsOfADeletedHabitAreKept()
    {
        var id = TimerHabit();
        _store.RecordFocusSession(Session(id, 25));
        _store.DeleteHabit(id);
        Assert.Single(_store.GetFocusSessions(Today, Today));
    }

    [Fact]
    public void ReadsByDateRange()
    {
        _store.RecordFocusSession(Session(null, 25) with { Date = Today.AddDays(-3) });
        _store.RecordFocusSession(Session(null, 25));
        Assert.Single(_store.GetFocusSessions(Today.AddDays(-1), Today));
        Assert.Equal(2, _store.GetAllFocusSessions().Count);
    }

    [Fact]
    public void SavesLoadsAndClearsTheRun()
    {
        Assert.Null(_store.GetFocusRun());
        var run = new FocusRun
        {
            Config = new FocusConfig { FocusMs = 50 * 60_000, BreaksEnabled = false },
            HabitId = 7, PhaseDate = Today, PhaseStartedAt = 123, PhaseDurationMs = 50 * 60_000, Running = true,
        };
        _store.SaveFocusRun(run);
        Assert.Equal(run, _store.GetFocusRun());

        _store.SaveFocusRun(run with { Running = false, AccumulatedMs = 5 });
        Assert.False(_store.GetFocusRun()!.Running);

        _store.ClearFocusRun();
        Assert.Null(_store.GetFocusRun());
    }

    [Fact]
    public void RaisesFocusAndEntryChanges()
    {
        var id = TimerHabit();
        var seen = new List<DataChange>();
        _store.Changed += seen.Add;
        _store.RecordFocusSession(Session(id, 25));
        _store.RecordFocusSession(Session(null, 25));
        Assert.Equal([DataChange.Focus | DataChange.Entries, DataChange.Focus], seen);
    }
}
