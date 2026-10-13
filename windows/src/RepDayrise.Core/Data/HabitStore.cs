using Microsoft.Data.Sqlite;
using RepDayrise.Core.Models;

namespace RepDayrise.Core.Data;

[Flags]
public enum DataChange { Habits = 1, Entries = 2, Groups = 4, Timers = 8 }

/// <summary>
/// The habit database: habits, entries, groups, reminders and timers, mirroring the Android Room schema.
///
/// The schema is ready for a future sync between devices. Synced rows carry a GUID <c>uid</c> and an <c>updatedAt</c>.
/// Deletes are soft (<c>deletedAt</c>). A cleared entry is kept as value 0 with a fresh <c>updatedAt</c> so a merge can
/// tell "cleared" from "never logged". Timers are per device and are not synced. Every write goes through this class.
/// </summary>
public sealed class HabitStore
{
    private const int SchemaVersion = 1;
    private readonly string _connectionString;

    /// <summary>Raised after a write commits, on the writing thread.</summary>
    public event Action<DataChange>? Changed;

    public HabitStore(string path)
    {
        Directory.CreateDirectory(Path.GetDirectoryName(Path.GetFullPath(path))!);
        _connectionString = new SqliteConnectionStringBuilder { DataSource = path, ForeignKeys = true, Pooling = true }.ToString();
        Migrate();
    }

    private SqliteConnection Open()
    {
        var c = new SqliteConnection(_connectionString);
        c.Open();
        return c;
    }

    private void Migrate()
    {
        using var c = Open();
        var version = Convert.ToInt32(Scalar(c, "PRAGMA user_version"));
        if (version >= SchemaVersion) return;
        Exec(c, """
            PRAGMA journal_mode = WAL;
            CREATE TABLE IF NOT EXISTS groups (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uid TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL,
                sortOrder INTEGER NOT NULL,
                collapsed INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                deletedAt INTEGER
            );
            CREATE TABLE IF NOT EXISTS habits (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uid TEXT NOT NULL UNIQUE,
                name TEXT NOT NULL,
                icon TEXT NOT NULL,
                colorIndex INTEGER NOT NULL,
                type TEXT NOT NULL,
                goal REAL NOT NULL,
                unit TEXT NOT NULL,
                scheduleType TEXT NOT NULL,
                daysMask INTEGER NOT NULL,
                timesPerPeriod INTEGER NOT NULL,
                groupId INTEGER REFERENCES groups(id) ON DELETE SET NULL,
                startDate INTEGER NOT NULL,
                createdAt INTEGER NOT NULL,
                archived INTEGER NOT NULL,
                archivedAt INTEGER,
                sortOrder INTEGER NOT NULL,
                note TEXT NOT NULL,
                updatedAt INTEGER NOT NULL,
                deletedAt INTEGER
            );
            CREATE TABLE IF NOT EXISTS entries (
                habitId INTEGER NOT NULL REFERENCES habits(id) ON DELETE CASCADE,
                date INTEGER NOT NULL,
                value REAL NOT NULL,
                updatedAt INTEGER NOT NULL,
                PRIMARY KEY (habitId, date)
            );
            CREATE INDEX IF NOT EXISTS index_entries_date ON entries(date);
            CREATE TABLE IF NOT EXISTS reminders (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                uid TEXT NOT NULL UNIQUE,
                habitId INTEGER NOT NULL REFERENCES habits(id) ON DELETE CASCADE,
                hour INTEGER NOT NULL,
                minute INTEGER NOT NULL,
                daysMask INTEGER NOT NULL,
                enabled INTEGER NOT NULL,
                updatedAt INTEGER NOT NULL,
                deletedAt INTEGER
            );
            CREATE INDEX IF NOT EXISTS index_reminders_habitId ON reminders(habitId);
            CREATE TABLE IF NOT EXISTS timers (
                habitId INTEGER PRIMARY KEY REFERENCES habits(id) ON DELETE CASCADE,
                date INTEGER NOT NULL,
                startedAt INTEGER NOT NULL,
                accumulatedMs INTEGER NOT NULL,
                running INTEGER NOT NULL
            );
            """);
        Exec(c, $"PRAGMA user_version = {SchemaVersion}");
    }

    // ---- Reads ------------------------------------------------------------------------------

    public IReadOnlyList<Habit> GetAllHabits()
    {
        using var c = Open();
        var reminders = ReadReminders(c, null).GroupBy(r => r.HabitId).ToDictionary(g => g.Key, g => (IReadOnlyList<Reminder>)g.ToList());
        var list = new List<Habit>();
        using var cmd = Command(c, "SELECT * FROM habits WHERE deletedAt IS NULL ORDER BY sortOrder, id");
        using var r = cmd.ExecuteReader();
        while (r.Read())
        {
            var h = ReadHabit(r);
            list.Add(h with { Reminders = reminders.GetValueOrDefault(h.Id, []) });
        }
        return list;
    }

    public Habit? GetHabit(long id) => GetAllHabits().FirstOrDefault(h => h.Id == id);

    public EntryMap GetEntries()
    {
        using var c = Open();
        var map = new EntryMap();
        using var cmd = Command(c, """
            SELECT e.habitId, e.date, e.value FROM entries e JOIN habits h ON h.id = e.habitId
            WHERE e.value > 0 AND h.deletedAt IS NULL ORDER BY e.date
            """);
        using var r = cmd.ExecuteReader();
        while (r.Read())
        {
            var habitId = r.GetInt64(0);
            if (!map.TryGetValue(habitId, out var days)) map[habitId] = days = new Dictionary<long, double>();
            days[r.GetInt64(1)] = r.GetDouble(2);
        }
        return map;
    }

    public double CurrentValue(long habitId, DateOnly date)
    {
        using var c = Open();
        return CurrentValue(c, habitId, date);
    }

    public IReadOnlyList<HabitGroup> GetGroups()
    {
        using var c = Open();
        var list = new List<HabitGroup>();
        using var cmd = Command(c, "SELECT id, uid, name, sortOrder, collapsed FROM groups WHERE deletedAt IS NULL ORDER BY sortOrder, id");
        using var r = cmd.ExecuteReader();
        while (r.Read())
            list.Add(new HabitGroup { Id = r.GetInt64(0), Uid = r.GetString(1), Name = r.GetString(2), SortOrder = r.GetInt32(3), Collapsed = r.GetInt64(4) != 0 });
        return list;
    }

    public IReadOnlyList<ActiveTimer> GetTimers()
    {
        using var c = Open();
        var list = new List<ActiveTimer>();
        using var cmd = Command(c, "SELECT habitId, date, startedAt, accumulatedMs, running FROM timers");
        using var r = cmd.ExecuteReader();
        while (r.Read()) list.Add(ReadTimer(r));
        return list;
    }

    public ActiveTimer? GetTimer(long habitId) => GetTimers().FirstOrDefault(t => t.HabitId == habitId);

    // ---- Habits -----------------------------------------------------------------------------

    public long SaveHabit(Habit habit)
    {
        var now = Clock.NowMs();
        long id;
        using (var c = Open())
        using (var tx = c.BeginTransaction())
        {
            var p = new Dictionary<string, object?>
            {
                ["$uid"] = habit.Uid, ["$name"] = habit.Name.Trim(), ["$icon"] = habit.Icon, ["$colorIndex"] = habit.ColorIndex,
                ["$type"] = habit.Type.ToString(), ["$goal"] = habit.Type == HabitType.CHECK ? 1.0 : habit.Goal, ["$unit"] = habit.Unit.Trim(),
                ["$scheduleType"] = habit.ScheduleType.ToString(), ["$daysMask"] = habit.DaysMask, ["$timesPerPeriod"] = Math.Max(habit.TimesPerPeriod, 1),
                ["$groupId"] = habit.GroupId, ["$startDate"] = habit.StartDate.ToEpochDay(), ["$createdAt"] = habit.CreatedAt,
                ["$archived"] = habit.Archived ? 1 : 0, ["$archivedAt"] = habit.ArchivedAt, ["$note"] = habit.Note, ["$now"] = now,
            };
            if (habit.Id == 0)
            {
                p["$sortOrder"] = Convert.ToInt32(Scalar(c, "SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM habits WHERE deletedAt IS NULL"));
                id = Convert.ToInt64(Scalar(c, """
                    INSERT INTO habits (uid, name, icon, colorIndex, type, goal, unit, scheduleType, daysMask, timesPerPeriod, groupId,
                        startDate, createdAt, archived, archivedAt, sortOrder, note, updatedAt)
                    VALUES ($uid, $name, $icon, $colorIndex, $type, $goal, $unit, $scheduleType, $daysMask, $timesPerPeriod, $groupId,
                        $startDate, $createdAt, $archived, $archivedAt, $sortOrder, $note, $now);
                    SELECT last_insert_rowid();
                    """, p));
            }
            else
            {
                id = habit.Id;
                p["$id"] = id;
                Exec(c, """
                    UPDATE habits SET name = $name, icon = $icon, colorIndex = $colorIndex, type = $type, goal = $goal, unit = $unit,
                        scheduleType = $scheduleType, daysMask = $daysMask, timesPerPeriod = $timesPerPeriod, groupId = $groupId,
                        startDate = $startDate, archived = $archived, archivedAt = $archivedAt, note = $note, updatedAt = $now
                    WHERE id = $id
                    """, p);
            }
            ReplaceReminders(c, id, habit.Reminders, now);
            tx.Commit();
        }
        Changed?.Invoke(DataChange.Habits);
        return id;
    }

    private static void ReplaceReminders(SqliteConnection c, long habitId, IReadOnlyList<Reminder> reminders, long now)
    {
        var keep = reminders.Select(r => r.Uid).ToHashSet();
        foreach (var existing in ReadReminders(c, habitId).Where(r => !keep.Contains(r.Uid)))
            Exec(c, "UPDATE reminders SET deletedAt = $now, updatedAt = $now WHERE uid = $uid", new() { ["$now"] = now, ["$uid"] = existing.Uid });
        foreach (var r in reminders)
            Exec(c, """
                INSERT INTO reminders (uid, habitId, hour, minute, daysMask, enabled, updatedAt) VALUES ($uid, $habitId, $hour, $minute, $daysMask, $enabled, $now)
                ON CONFLICT(uid) DO UPDATE SET hour = $hour, minute = $minute, daysMask = $daysMask, enabled = $enabled, updatedAt = $now, deletedAt = NULL
                """, new()
            {
                ["$uid"] = r.Uid, ["$habitId"] = habitId, ["$hour"] = r.Hour, ["$minute"] = r.Minute, ["$daysMask"] = r.DaysMask,
                ["$enabled"] = r.Enabled ? 1 : 0, ["$now"] = now,
            });
    }

    public void SetArchived(long id, bool archived)
    {
        var now = Clock.NowMs();
        Write(DataChange.Habits, "UPDATE habits SET archived = $a, archivedAt = $at, updatedAt = $now WHERE id = $id",
            new() { ["$a"] = archived ? 1 : 0, ["$at"] = archived ? now : null, ["$now"] = now, ["$id"] = id });
    }

    public void DeleteHabit(long id)
    {
        var now = Clock.NowMs();
        Write(DataChange.Habits | DataChange.Entries | DataChange.Timers, """
            UPDATE habits SET deletedAt = $now, updatedAt = $now WHERE id = $id;
            UPDATE reminders SET deletedAt = $now, updatedAt = $now WHERE habitId = $id AND deletedAt IS NULL;
            DELETE FROM timers WHERE habitId = $id;
            """, new() { ["$now"] = now, ["$id"] = id });
    }

    public void Reorder(IReadOnlyList<long> ids)
    {
        var now = Clock.NowMs();
        using (var c = Open())
        using (var tx = c.BeginTransaction())
        {
            for (var i = 0; i < ids.Count; i++)
                Exec(c, "UPDATE habits SET sortOrder = $i, updatedAt = $now WHERE id = $id", new() { ["$i"] = i, ["$now"] = now, ["$id"] = ids[i] });
            tx.Commit();
        }
        Changed?.Invoke(DataChange.Habits);
    }

    // ---- Entries ----------------------------------------------------------------------------

    public void SetValue(long habitId, DateOnly date, double value)
    {
        using (var c = Open()) SetValue(c, habitId, date, value);
        Changed?.Invoke(DataChange.Entries);
    }

    private static void SetValue(SqliteConnection c, long habitId, DateOnly date, double value) =>
        Exec(c, """
            INSERT INTO entries (habitId, date, value, updatedAt) VALUES ($h, $d, $v, $now)
            ON CONFLICT(habitId, date) DO UPDATE SET value = $v, updatedAt = $now
            """, new() { ["$h"] = habitId, ["$d"] = date.ToEpochDay(), ["$v"] = Math.Max(value, 0.0), ["$now"] = Clock.NowMs() });

    public void Toggle(Habit habit, DateOnly date)
    {
        var current = CurrentValue(habit.Id, date);
        var done = current + 1e-9 >= habit.EffectiveGoal;
        SetValue(habit.Id, date, done ? 0.0 : habit.EffectiveGoal);
    }

    public void Increment(Habit habit, DateOnly date, double delta) =>
        SetValue(habit.Id, date, Math.Max(CurrentValue(habit.Id, date) + delta, 0.0));

    // ---- Groups -----------------------------------------------------------------------------

    public long CreateGroup(string name)
    {
        long id;
        using (var c = Open())
        {
            var next = Convert.ToInt32(Scalar(c, "SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM groups WHERE deletedAt IS NULL"));
            id = Convert.ToInt64(Scalar(c, """
                INSERT INTO groups (uid, name, sortOrder, collapsed, updatedAt) VALUES ($uid, $name, $s, 0, $now);
                SELECT last_insert_rowid();
                """, new() { ["$uid"] = Guid.NewGuid().ToString(), ["$name"] = name.Trim(), ["$s"] = next, ["$now"] = Clock.NowMs() }));
        }
        Changed?.Invoke(DataChange.Groups);
        return id;
    }

    public void RenameGroup(long id, string name) =>
        Write(DataChange.Groups, "UPDATE groups SET name = $name, updatedAt = $now WHERE id = $id", new() { ["$name"] = name.Trim(), ["$now"] = Clock.NowMs(), ["$id"] = id });

    /// <summary>Collapsed state is a view preference, so it doesn't bump updatedAt.</summary>
    public void SetGroupCollapsed(long id, bool collapsed) =>
        Write(DataChange.Groups, "UPDATE groups SET collapsed = $c WHERE id = $id", new() { ["$c"] = collapsed ? 1 : 0, ["$id"] = id });

    public void DeleteGroup(long id)
    {
        var now = Clock.NowMs();
        Write(DataChange.Groups | DataChange.Habits, """
            UPDATE habits SET groupId = NULL, updatedAt = $now WHERE groupId = $id;
            UPDATE groups SET deletedAt = $now, updatedAt = $now WHERE id = $id;
            """, new() { ["$now"] = now, ["$id"] = id });
    }

    // ---- Timers -----------------------------------------------------------------------------

    public void StartTimer(Habit habit, DateOnly date)
    {
        var existing = GetTimer(habit.Id);
        var now = Clock.NowMs();
        var timer = existing != null && existing.Date == date
            ? existing with { StartedAt = now, Running = true }
            : new ActiveTimer(habit.Id, date, now, 0L, true);
        UpsertTimer(timer);
    }

    public void PauseTimer(long habitId)
    {
        var t = GetTimer(habitId);
        if (t is not { Running: true }) return;
        var now = Clock.NowMs();
        UpsertTimer(t with { AccumulatedMs = t.AccumulatedMs + Math.Max(now - t.StartedAt, 0), Running = false });
    }

    /// <summary>Stops the timer and commits elapsed minutes to the day's entry.</summary>
    public void FinishTimer(Habit habit)
    {
        var t = GetTimer(habit.Id);
        if (t == null) return;
        var minutes = t.ElapsedMs() / 60000.0;
        CancelTimer(habit.Id);
        if (minutes > 0.05) Increment(habit, t.Date, minutes);
    }

    public void CancelTimer(long habitId) => Write(DataChange.Timers, "DELETE FROM timers WHERE habitId = $h", new() { ["$h"] = habitId });

    private void UpsertTimer(ActiveTimer t) =>
        Write(DataChange.Timers, "INSERT OR REPLACE INTO timers (habitId, date, startedAt, accumulatedMs, running) VALUES ($h, $d, $s, $a, $r)",
            new() { ["$h"] = t.HabitId, ["$d"] = t.Date.ToEpochDay(), ["$s"] = t.StartedAt, ["$a"] = t.AccumulatedMs, ["$r"] = t.Running ? 1 : 0 });

    // ---- Helpers ----------------------------------------------------------------------------

    private void Write(DataChange change, string sql, Dictionary<string, object?> parameters)
    {
        using (var c = Open()) Exec(c, sql, parameters);
        Changed?.Invoke(change);
    }

    private static double CurrentValue(SqliteConnection c, long habitId, DateOnly date) =>
        Scalar(c, "SELECT value FROM entries WHERE habitId = $h AND date = $d", new() { ["$h"] = habitId, ["$d"] = date.ToEpochDay() }) is double v ? v : 0.0;

    private static List<Reminder> ReadReminders(SqliteConnection c, long? habitId)
    {
        var sql = "SELECT id, uid, habitId, hour, minute, daysMask, enabled FROM reminders WHERE deletedAt IS NULL"
                  + (habitId != null ? " AND habitId = $h" : "") + " ORDER BY hour, minute";
        using var cmd = Command(c, sql, habitId != null ? new() { ["$h"] = habitId } : null);
        using var r = cmd.ExecuteReader();
        var list = new List<Reminder>();
        while (r.Read())
            list.Add(new Reminder
            {
                Id = r.GetInt64(0), Uid = r.GetString(1), HabitId = r.GetInt64(2), Hour = r.GetInt32(3), Minute = r.GetInt32(4),
                DaysMask = r.GetInt32(5), Enabled = r.GetInt64(6) != 0,
            });
        return list;
    }

    private static Habit ReadHabit(SqliteDataReader r) => new()
    {
        Id = r.GetInt64(r.GetOrdinal("id")),
        Uid = r.GetString(r.GetOrdinal("uid")),
        Name = r.GetString(r.GetOrdinal("name")),
        Icon = r.GetString(r.GetOrdinal("icon")),
        ColorIndex = r.GetInt32(r.GetOrdinal("colorIndex")),
        Type = Enum.TryParse<HabitType>(r.GetString(r.GetOrdinal("type")), out var t) ? t : HabitType.CHECK,
        Goal = r.GetDouble(r.GetOrdinal("goal")),
        Unit = r.GetString(r.GetOrdinal("unit")),
        ScheduleType = Enum.TryParse<ScheduleType>(r.GetString(r.GetOrdinal("scheduleType")), out var s) ? s : ScheduleType.DAILY,
        DaysMask = r.GetInt32(r.GetOrdinal("daysMask")),
        TimesPerPeriod = r.GetInt32(r.GetOrdinal("timesPerPeriod")),
        GroupId = r.IsDBNull(r.GetOrdinal("groupId")) ? null : r.GetInt64(r.GetOrdinal("groupId")),
        StartDate = EpochDay.ToDate(r.GetInt64(r.GetOrdinal("startDate"))),
        CreatedAt = r.GetInt64(r.GetOrdinal("createdAt")),
        Archived = r.GetInt64(r.GetOrdinal("archived")) != 0,
        ArchivedAt = r.IsDBNull(r.GetOrdinal("archivedAt")) ? null : r.GetInt64(r.GetOrdinal("archivedAt")),
        SortOrder = r.GetInt32(r.GetOrdinal("sortOrder")),
        Note = r.GetString(r.GetOrdinal("note")),
    };

    private static ActiveTimer ReadTimer(SqliteDataReader r) =>
        new(r.GetInt64(0), EpochDay.ToDate(r.GetInt64(1)), r.GetInt64(2), r.GetInt64(3), r.GetInt64(4) != 0);

    private static SqliteCommand Command(SqliteConnection c, string sql, Dictionary<string, object?>? parameters = null)
    {
        var cmd = c.CreateCommand();
        cmd.CommandText = sql;
        if (parameters != null)
            foreach (var (k, v) in parameters) cmd.Parameters.AddWithValue(k, v ?? DBNull.Value);
        return cmd;
    }

    private static void Exec(SqliteConnection c, string sql, Dictionary<string, object?>? parameters = null)
    {
        using var cmd = Command(c, sql, parameters);
        cmd.ExecuteNonQuery();
    }

    private static object? Scalar(SqliteConnection c, string sql, Dictionary<string, object?>? parameters = null)
    {
        using var cmd = Command(c, sql, parameters);
        return cmd.ExecuteScalar();
    }
}
