using Microsoft.Data.Sqlite;
using RepDayrise.Core.Data;
using RepDayrise.Core.Models;

namespace RepDayrise.Core.Tests;

public sealed class HabitStoreTests : IDisposable
{
    private readonly string _dir = Path.Combine(Path.GetTempPath(), "dayrise-tests-" + Guid.NewGuid().ToString("N"));
    private readonly string _path;
    private readonly HabitStore _store;
    private static readonly DateOnly Today = new(2026, 9, 15);

    public HabitStoreTests()
    {
        _path = Path.Combine(_dir, "dayrise.db");
        _store = new HabitStore(_path);
    }

    public void Dispose()
    {
        SqliteConnection.ClearAllPools();
        try { Directory.Delete(_dir, recursive: true); } catch { /* best effort */ }
    }

    private long Raw(string sql)
    {
        using var c = new SqliteConnection($"Data Source={_path}");
        c.Open();
        using var cmd = c.CreateCommand();
        cmd.CommandText = sql;
        return Convert.ToInt64(cmd.ExecuteScalar());
    }

    [Fact]
    public void SavesAndReadsBackAHabitWithReminders()
    {
        var id = _store.SaveHabit(new Habit
        {
            Name = "  Water ", Type = HabitType.COUNT, Goal = 8, Unit = "glasses", StartDate = Today,
            Reminders = [new Reminder { Hour = 9, Minute = 30 }],
        });
        var h = _store.GetHabit(id)!;
        Assert.Equal("Water", h.Name);
        Assert.Equal(HabitType.COUNT, h.Type);
        Assert.Equal(Today, h.StartDate);
        Assert.Equal("9:30 AM", h.Reminders.Single().TimeLabel());
    }

    [Fact]
    public void NewHabitsGoToTheEnd()
    {
        _store.SaveHabit(new Habit { Name = "A" });
        _store.SaveHabit(new Habit { Name = "B" });
        Assert.Equal(["A", "B"], _store.GetAllHabits().Select(h => h.Name));
    }

    [Fact]
    public void ToggleAndIncrementFollowTheGoal()
    {
        var check = _store.GetHabit(_store.SaveHabit(new Habit { Name = "Read" }))!;
        _store.Toggle(check, Today);
        Assert.Equal(1.0, _store.CurrentValue(check.Id, Today));
        _store.Toggle(check, Today);
        Assert.Equal(0.0, _store.CurrentValue(check.Id, Today));

        var count = _store.GetHabit(_store.SaveHabit(new Habit { Name = "Water", Type = HabitType.COUNT, Goal = 8 }))!;
        _store.Increment(count, Today, 3);
        _store.Increment(count, Today, -5);
        Assert.Equal(0.0, _store.CurrentValue(count.Id, Today));
    }

    [Fact]
    public void ClearedEntriesStayAsTombstonesButAreNotRead()
    {
        var h = _store.GetHabit(_store.SaveHabit(new Habit { Name = "Read" }))!;
        _store.SetValue(h.Id, Today, 1);
        _store.SetValue(h.Id, Today, 0);
        Assert.Empty(_store.GetEntries());
        Assert.Equal(1, Raw("SELECT COUNT(*) FROM entries"));
    }

    [Fact]
    public void DeletingAHabitIsSoftAndKeepsItsUid()
    {
        var h = _store.GetHabit(_store.SaveHabit(new Habit { Name = "Read", Reminders = [new Reminder { Hour = 8 }] }))!;
        _store.SetValue(h.Id, Today, 1);
        _store.DeleteHabit(h.Id);
        Assert.Empty(_store.GetAllHabits());
        Assert.Empty(_store.GetEntries());
        Assert.Equal(1, Raw($"SELECT COUNT(*) FROM habits WHERE uid = '{h.Uid}' AND deletedAt IS NOT NULL"));
        Assert.Equal(1, Raw("SELECT COUNT(*) FROM reminders WHERE deletedAt IS NOT NULL"));
    }

    [Fact]
    public void ReplacingRemindersSoftDeletesTheDroppedOnes()
    {
        var h = _store.GetHabit(_store.SaveHabit(new Habit { Name = "Read", Reminders = [new Reminder { Hour = 8 }, new Reminder { Hour = 20 }] }))!;
        var keep = h.Reminders.Single(r => r.Hour == 20);
        _store.SaveHabit(h with { Reminders = [keep with { Minute = 15 }] });
        var after = _store.GetHabit(h.Id)!.Reminders.Single();
        Assert.Equal(keep.Uid, after.Uid);
        Assert.Equal(15, after.Minute);
        Assert.Equal(1, Raw("SELECT COUNT(*) FROM reminders WHERE deletedAt IS NOT NULL"));
    }

    [Fact]
    public void DeletingAGroupDetachesItsHabits()
    {
        var g = _store.CreateGroup("Morning");
        var id = _store.SaveHabit(new Habit { Name = "Read", GroupId = g });
        _store.DeleteGroup(g);
        Assert.Empty(_store.GetGroups());
        Assert.Null(_store.GetHabit(id)!.GroupId);
    }

    [Fact]
    public void FinishingATimerLogsMinutes()
    {
        var h = _store.GetHabit(_store.SaveHabit(new Habit { Name = "Focus", Type = HabitType.TIMER, Goal = 30 }))!;
        _store.StartTimer(h, Today);
        // Pretend it ran for ten minutes.
        Raw($"UPDATE timers SET startedAt = startedAt - 600000 WHERE habitId = {h.Id}; SELECT 0");
        _store.FinishTimer(h);
        Assert.Null(_store.GetTimer(h.Id));
        Assert.InRange(_store.CurrentValue(h.Id, Today), 9.9, 10.5);
    }

    [Fact]
    public void WritesRaiseChanged()
    {
        DataChange seen = 0;
        _store.Changed += c => seen |= c;
        var id = _store.SaveHabit(new Habit { Name = "Read" });
        _store.SetValue(id, Today, 1);
        Assert.True(seen.HasFlag(DataChange.Habits));
        Assert.True(seen.HasFlag(DataChange.Entries));
    }
}
