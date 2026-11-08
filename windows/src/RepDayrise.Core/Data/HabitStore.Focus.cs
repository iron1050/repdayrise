using System.Text.Json;
using Microsoft.Data.Sqlite;
using RepDayrise.Core.Models;

namespace RepDayrise.Core.Data;

/// <summary>Focus sessions: the finished-session log (synced in future) and the device-local run in progress.</summary>
public sealed partial class HabitStore
{
    private const string SchemaV2 = """
        CREATE TABLE IF NOT EXISTS focus_sessions (
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            uid TEXT NOT NULL UNIQUE,
            habitId INTEGER REFERENCES habits(id) ON DELETE SET NULL,
            habitUid TEXT,
            date INTEGER NOT NULL,
            startedAt INTEGER NOT NULL,
            endedAt INTEGER NOT NULL,
            plannedMs INTEGER NOT NULL,
            focusedMs INTEGER NOT NULL,
            status TEXT NOT NULL,
            creditedMinutes REAL NOT NULL,
            note TEXT NOT NULL DEFAULT '',
            updatedAt INTEGER NOT NULL,
            deletedAt INTEGER
        );
        CREATE INDEX IF NOT EXISTS index_focus_sessions_date ON focus_sessions(date);
        CREATE TABLE IF NOT EXISTS focus_run (
            id INTEGER PRIMARY KEY CHECK (id = 1),
            json TEXT NOT NULL,
            updatedAt INTEGER NOT NULL
        );
        """;

    /// <summary>Below this many minutes nothing is credited, the same threshold as <see cref="FinishTimer"/>.</summary>
    private const double MinCreditMinutes = 0.05;

    private static readonly JsonSerializerOptions RunJson = new() { WriteIndented = false };

    /// <summary>
    /// Records a finished focus phase and, if it's tagged to a live timer habit, adds its minutes to that day's entry.
    /// Idempotent by <see cref="FocusSession.Uid"/>: recording the same session twice credits it once.
    /// Returns the session as stored (with its credit), or null if it was already recorded.
    /// </summary>
    public FocusSession? RecordFocusSession(FocusSession session)
    {
        var now = Clock.NowMs();
        FocusSession stored;
        using (var c = Open())
        using (var tx = c.BeginTransaction())
        {
            var exists = Scalar(c, "SELECT 1 FROM focus_sessions WHERE uid = $uid", new() { ["$uid"] = session.Uid }) != null;
            if (exists) return null;

            var habit = session.HabitId is long hid ? LiveHabit(c, hid) : null;
            var minutes = session.FocusedMs / 60000.0;
            var credit = habit is { Type: HabitType.TIMER } && minutes > MinCreditMinutes ? minutes : 0.0;
            stored = session with
            {
                HabitId = habit?.Id,
                HabitUid = habit?.Uid,
                CreditedMinutes = credit,
            };
            stored = stored with
            {
                Id = Convert.ToInt64(Scalar(c, """
                    INSERT INTO focus_sessions (uid, habitId, habitUid, date, startedAt, endedAt, plannedMs, focusedMs, status,
                        creditedMinutes, note, updatedAt)
                    VALUES ($uid, $habitId, $habitUid, $date, $startedAt, $endedAt, $plannedMs, $focusedMs, $status,
                        $credited, $note, $now);
                    SELECT last_insert_rowid();
                    """, SessionParams(stored, now))),
            };
            if (credit > 0) AddToEntry(c, habit!.Id, stored.Date, credit);
            tx.Commit();
        }
        Changed?.Invoke(DataChange.Focus | (stored.CreditedMinutes > 0 ? DataChange.Entries : 0));
        return stored;
    }

    /// <summary>Soft-deletes a session and takes its credited minutes back off the habit's entry.</summary>
    public void DeleteFocusSession(string uid)
    {
        var now = Clock.NowMs();
        var uncredited = false;
        using (var c = Open())
        using (var tx = c.BeginTransaction())
        {
            var s = ReadSessions(c, "uid = $uid AND deletedAt IS NULL", new() { ["$uid"] = uid }).FirstOrDefault();
            if (s == null) return;
            if (s is { HabitId: long hid, CreditedMinutes: > 0 })
            {
                AddToEntry(c, hid, s.Date, -s.CreditedMinutes);
                uncredited = true;
            }
            Exec(c, "UPDATE focus_sessions SET deletedAt = $now, updatedAt = $now, creditedMinutes = 0 WHERE uid = $uid",
                new() { ["$now"] = now, ["$uid"] = uid });
            tx.Commit();
        }
        Changed?.Invoke(DataChange.Focus | (uncredited ? DataChange.Entries : 0));
    }

    /// <summary>Moves a session to another habit (or none), moving its credited minutes with it.</summary>
    public void RetagFocusSession(string uid, long? habitId)
    {
        var now = Clock.NowMs();
        using (var c = Open())
        using (var tx = c.BeginTransaction())
        {
            var s = ReadSessions(c, "uid = $uid AND deletedAt IS NULL", new() { ["$uid"] = uid }).FirstOrDefault();
            if (s == null) return;
            if (s is { HabitId: long oldId, CreditedMinutes: > 0 }) AddToEntry(c, oldId, s.Date, -s.CreditedMinutes);

            var habit = habitId is long hid ? LiveHabit(c, hid) : null;
            var minutes = s.FocusedMs / 60000.0;
            var credit = habit is { Type: HabitType.TIMER } && minutes > MinCreditMinutes ? minutes : 0.0;
            if (credit > 0) AddToEntry(c, habit!.Id, s.Date, credit);
            Exec(c, "UPDATE focus_sessions SET habitId = $h, habitUid = $hu, creditedMinutes = $cr, updatedAt = $now WHERE uid = $uid",
                new() { ["$h"] = habit?.Id, ["$hu"] = habit?.Uid, ["$cr"] = credit, ["$now"] = now, ["$uid"] = uid });
            tx.Commit();
        }
        Changed?.Invoke(DataChange.Focus | DataChange.Entries);
    }

    /// <summary>Recorded sessions whose date falls in [from, to], oldest first. Deleted sessions are left out.</summary>
    public IReadOnlyList<FocusSession> GetFocusSessions(DateOnly from, DateOnly to)
    {
        using var c = Open();
        return ReadSessions(c, "deletedAt IS NULL AND date BETWEEN $from AND $to",
            new() { ["$from"] = from.ToEpochDay(), ["$to"] = to.ToEpochDay() });
    }

    public IReadOnlyList<FocusSession> GetAllFocusSessions()
    {
        using var c = Open();
        return ReadSessions(c, "deletedAt IS NULL", null);
    }

    // ---- The run in progress (device-local) ----------------------------------------------------

    public FocusRun? GetFocusRun()
    {
        using var c = Open();
        if (Scalar(c, "SELECT json FROM focus_run WHERE id = 1") is not string json) return null;
        try { return JsonSerializer.Deserialize<FocusRun>(json, RunJson); }
        catch (JsonException) { return null; } // a run we can't read is not worth crashing over
    }

    public void SaveFocusRun(FocusRun run) =>
        Write(DataChange.Focus, """
            INSERT INTO focus_run (id, json, updatedAt) VALUES (1, $json, $now)
            ON CONFLICT(id) DO UPDATE SET json = $json, updatedAt = $now
            """, new() { ["$json"] = JsonSerializer.Serialize(run, RunJson), ["$now"] = Clock.NowMs() });

    public void ClearFocusRun() => Write(DataChange.Focus, "DELETE FROM focus_run", new());

    // ---- Helpers ----------------------------------------------------------------------------

    private static Habit? LiveHabit(SqliteConnection c, long id)
    {
        using var cmd = Command(c, "SELECT * FROM habits WHERE id = $id AND deletedAt IS NULL", new() { ["$id"] = id });
        using var r = cmd.ExecuteReader();
        return r.Read() ? ReadHabit(r) : null;
    }

    private static void AddToEntry(SqliteConnection c, long habitId, DateOnly date, double minutes) =>
        SetValue(c, habitId, date, Math.Max(CurrentValue(c, habitId, date) + minutes, 0.0));

    private static Dictionary<string, object?> SessionParams(FocusSession s, long now) => new()
    {
        ["$uid"] = s.Uid, ["$habitId"] = s.HabitId, ["$habitUid"] = s.HabitUid, ["$date"] = s.Date.ToEpochDay(),
        ["$startedAt"] = s.StartedAt, ["$endedAt"] = s.EndedAt, ["$plannedMs"] = s.PlannedMs, ["$focusedMs"] = s.FocusedMs,
        ["$status"] = s.Status.ToString(), ["$credited"] = s.CreditedMinutes, ["$note"] = s.Note, ["$now"] = now,
    };

    private static List<FocusSession> ReadSessions(SqliteConnection c, string where, Dictionary<string, object?>? parameters)
    {
        using var cmd = Command(c, $"""
            SELECT id, uid, habitId, habitUid, date, startedAt, endedAt, plannedMs, focusedMs, status, creditedMinutes, note
            FROM focus_sessions WHERE {where} ORDER BY startedAt, id
            """, parameters);
        using var r = cmd.ExecuteReader();
        var list = new List<FocusSession>();
        while (r.Read())
            list.Add(new FocusSession
            {
                Id = r.GetInt64(0),
                Uid = r.GetString(1),
                HabitId = r.IsDBNull(2) ? null : r.GetInt64(2),
                HabitUid = r.IsDBNull(3) ? null : r.GetString(3),
                Date = EpochDay.ToDate(r.GetInt64(4)),
                StartedAt = r.GetInt64(5),
                EndedAt = r.GetInt64(6),
                PlannedMs = r.GetInt64(7),
                FocusedMs = r.GetInt64(8),
                Status = Enum.TryParse<FocusSessionStatus>(r.GetString(9), out var st) ? st : FocusSessionStatus.COMPLETED,
                CreditedMinutes = r.GetDouble(10),
                Note = r.GetString(11),
            });
        return list;
    }
}
