using Microsoft.Data.Sqlite;
using RepDayrise.Core.Data;
using RepDayrise.Core.Domain;
using RepDayrise.Core.Focus;
using RepDayrise.Core.Models;

namespace RepDayrise.Core.Tests;

public sealed class FocusServiceTests : IDisposable
{
    private const long Min = 60_000;
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "dayrise-tests-" + Guid.NewGuid().ToString("N"));
    private readonly string _path;
    private readonly HabitStore _store;
    private readonly FakeAlarm _alarm = new();
    private long _now = new DateTimeOffset(2026, 11, 8, 9, 0, 0, TimeSpan.Zero).ToUnixTimeMilliseconds();
    private static readonly DateOnly Day = new(2026, 11, 8);

    private sealed class FakeAlarm : IFocusAlarm
    {
        public FocusAlarm? Pending;
        public int Cancels;
        public void Schedule(FocusAlarm alarm) => Pending = alarm;
        public void Cancel() { Pending = null; Cancels++; }
    }

    public FocusServiceTests()
    {
        _path = Path.Combine(_dir, "dayrise.db");
        _store = new HabitStore(_path);
    }

    public void Dispose()
    {
        SqliteConnection.ClearAllPools();
        try { Directory.Delete(_dir, recursive: true); } catch { /* best effort */ }
    }

    private FocusService NewService(HabitStore? store = null) =>
        new(store ?? _store, _alarm, () => _now, ms => DateOnly.FromDateTime(DateTimeOffset.FromUnixTimeMilliseconds(ms).UtcDateTime));

    private long TimerHabit() => _store.SaveHabit(new Habit { Name = "Deep work", Type = HabitType.TIMER, Goal = 120, StartDate = Day });

    [Fact]
    public void AlarmFollowsStartPauseAndResume()
    {
        var focus = NewService();
        focus.Start(new FocusConfig(), null);
        Assert.Equal(_now + 25 * Min, _alarm.Pending!.At);

        _now += 5 * Min;
        focus.Pause();
        Assert.Null(_alarm.Pending);

        _now += 60 * Min;
        focus.Resume();
        Assert.Equal(_now + 20 * Min, _alarm.Pending!.At);
    }

    [Fact]
    public void CompletedFocusIsCreditedToTheHabit()
    {
        var id = TimerHabit();
        var focus = NewService();
        FocusSession? recorded = null;
        focus.SessionRecorded += s => recorded = s;

        focus.Start(new FocusConfig(), id);
        _now += 25 * Min;
        focus.Tick();

        Assert.Equal(25, _store.CurrentValue(id, Day), 6);
        Assert.Equal(25, recorded!.CreditedMinutes, 6);
        Assert.Equal(FocusPhase.SHORT_BREAK, focus.Current!.Phase);
    }

    [Fact]
    public void ARestartResumesTheRunAndCreditsOnce()
    {
        var id = TimerHabit();
        NewService().Start(new FocusConfig(), id);

        // The app closes; it opens again after the phase ended.
        _now += 40 * Min;
        var reopened = NewService(new HabitStore(_path));
        reopened.Tick();
        reopened.Tick();
        var again = NewService(new HabitStore(_path));
        again.Tick();

        Assert.Equal(25, _store.CurrentValue(id, Day), 6);
        Assert.Single(_store.GetFocusSessions(Day, Day));
        Assert.True(again.Current!.AwaitingStart);
    }

    [Fact]
    public void StartingFinishesThatHabitsManualTimerFirst()
    {
        var id = TimerHabit();
        var habit = _store.GetHabit(id)!;
        _store.StartTimer(habit, Day);
        // Back-date the manual timer so it has ten minutes on it.
        _store.PauseTimer(id);
        using (var c = new SqliteConnection($"Data Source={_path}"))
        {
            c.Open();
            using var cmd = c.CreateCommand();
            cmd.CommandText = $"UPDATE timers SET accumulatedMs = {10 * Min} WHERE habitId = {id}";
            cmd.ExecuteNonQuery();
        }

        NewService().Start(new FocusConfig(), id);
        Assert.Null(_store.GetTimer(id));
        Assert.Equal(10, _store.CurrentValue(id, Day), 6);
    }

    [Fact]
    public void QuietTicksDoNotWrite()
    {
        var focus = NewService();
        focus.Start(new FocusConfig(), null);
        var changes = 0;
        _store.Changed += _ => changes++;
        _now += Min;
        focus.Tick();
        focus.Tick();
        Assert.Equal(0, changes);
    }

    [Fact]
    public void StopClearsTheRun()
    {
        var focus = NewService();
        focus.Start(new FocusConfig(), null);
        _now += 10 * Min;
        focus.Stop();
        Assert.Null(focus.Current);
        Assert.Null(_alarm.Pending);
        Assert.Single(_store.GetFocusSessions(Day, Day));
    }

    [Fact]
    public void SettingsWithoutFocusLoadDefaults()
    {
        var path = Path.Combine(_dir, "settings.json");
        File.WriteAllText(path, """{"themeMode":2,"weekStartsMonday":false}""");
        var s = new SettingsStore(path).Current;
        Assert.False(s.WeekStartsMonday);
        Assert.Equal(25, s.Focus.FocusMinutes);
        Assert.True(s.Focus.BreaksEnabled);
        Assert.Equal(SkyQuality.High, s.SkyQuality);

        var store = new SettingsStore(path);
        store.Update(x => x with { Focus = x.Focus with { FocusMinutes = 50 } });
        Assert.Equal(50, new SettingsStore(path).Current.Focus.FocusMinutes);
        Assert.Equal(50 * Min, new SettingsStore(path).Current.Focus.ToConfig().FocusMs);
    }
}
