package com.repdayrise.app.data

import android.content.Context
import androidx.room.withTransaction
import com.repdayrise.app.data.db.DayriseDatabase
import com.repdayrise.app.data.db.EntryEntity
import com.repdayrise.app.data.db.GroupEntity
import com.repdayrise.app.data.db.HabitEntity
import com.repdayrise.app.data.db.ReminderEntity
import com.repdayrise.app.data.model.HabitType
import com.repdayrise.app.data.model.HealthSource
import com.repdayrise.app.data.model.ScheduleType
import com.repdayrise.app.notifications.TimerNotifier
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** What an import file contains, shown before the user commits to replacing their data. */
data class BackupSummary(val habits: Int, val entries: Int, val groups: Int, val exportedAt: Long)

class BackupException(message: String) : Exception(message)

/**
 * Whole-database JSON export and import. Ids are kept as they are in the file, so entries and reminders stay attached
 * to their habits. Import replaces everything in one transaction; if the file is bad nothing changes.
 */
class DataBackup(private val context: Context, private val db: DayriseDatabase, private val repository: HabitRepository) {

    suspend fun export(): String {
        val groups = db.groupDao().getAll()
        val habits = db.habitDao().getAll()
        val reminders = db.reminderDao().getAll()
        val entries = db.entryDao().getAll()
        return JSONObject().apply {
            put("app", APP)
            put("version", VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("groups", JSONArray(groups.map { g ->
                JSONObject().put("id", g.id).put("name", g.name).put("sortOrder", g.sortOrder).put("collapsed", g.collapsed)
            }))
            put("habits", JSONArray(habits.map { h ->
                JSONObject()
                    .put("id", h.id).put("name", h.name).put("icon", h.icon).put("colorIndex", h.colorIndex)
                    .put("type", h.type.name).put("goal", h.goal).put("unit", h.unit)
                    .put("scheduleType", h.scheduleType.name).put("daysMask", h.daysMask).put("timesPerPeriod", h.timesPerPeriod)
                    .put("groupId", h.groupId ?: JSONObject.NULL).put("startDate", h.startDate).put("createdAt", h.createdAt)
                    .put("archived", h.archived).put("archivedAt", h.archivedAt ?: JSONObject.NULL)
                    .put("sortOrder", h.sortOrder).put("healthSource", h.healthSource.name).put("note", h.note)
            }))
            put("reminders", JSONArray(reminders.map { r ->
                JSONObject().put("habitId", r.habitId).put("hour", r.hour).put("minute", r.minute)
                    .put("daysMask", r.daysMask).put("enabled", r.enabled)
            }))
            // Compact [habitId, epochDay, value] triples keep a year of entries small.
            put("entries", JSONArray(entries.map { e -> JSONArray().put(e.habitId).put(e.date).put(e.value) }))
        }.toString(1)
    }

    /** Parses and checks a file without touching the database. */
    fun inspect(json: String): BackupSummary {
        val root = parse(json)
        return BackupSummary(
            habits = root.getJSONArray("habits").length(),
            entries = root.getJSONArray("entries").length(),
            groups = root.getJSONArray("groups").length(),
            exportedAt = root.optLong("exportedAt"),
        )
    }

    suspend fun import(json: String): BackupSummary {
        val root = parse(json)
        val summary = inspect(json)
        val now = System.currentTimeMillis()
        val groups = root.getJSONArray("groups").objects().map { o ->
            GroupEntity(o.getLong("id"), o.getString("name"), o.optInt("sortOrder"), o.optBoolean("collapsed"))
        }
        val habits = root.getJSONArray("habits").objects().map { o ->
            HabitEntity(
                id = o.getLong("id"), name = o.getString("name"), icon = o.optString("icon", "sun"), colorIndex = o.optInt("colorIndex"),
                type = enumOr(o.optString("type"), HabitType.CHECK), goal = o.optDouble("goal", 1.0), unit = o.optString("unit"),
                scheduleType = enumOr(o.optString("scheduleType"), ScheduleType.DAILY), daysMask = o.optInt("daysMask", 127),
                timesPerPeriod = o.optInt("timesPerPeriod", 3),
                groupId = if (o.isNull("groupId")) null else o.getLong("groupId"),
                startDate = o.getLong("startDate"), createdAt = o.optLong("createdAt", now),
                archived = o.optBoolean("archived"), archivedAt = if (o.isNull("archivedAt")) null else o.getLong("archivedAt"),
                sortOrder = o.optInt("sortOrder"), healthSource = enumOr(o.optString("healthSource"), HealthSource.NONE),
                note = o.optString("note"),
            )
        }
        val habitIds = habits.map { it.id }.toSet()
        val reminders = root.getJSONArray("reminders").objects()
            .filter { it.getLong("habitId") in habitIds }
            .map { o -> ReminderEntity(0, o.getLong("habitId"), o.getInt("hour"), o.getInt("minute"), o.optInt("daysMask", 127), o.optBoolean("enabled", true)) }
        val entries = (0 until root.getJSONArray("entries").length()).mapNotNull { i ->
            val t = root.getJSONArray("entries").getJSONArray(i)
            val habitId = t.getLong(0)
            if (habitId in habitIds) EntryEntity(habitId, t.getLong(1), t.getDouble(2), now) else null
        }

        val oldTimers = db.timerDao().getAll().map { it.habitId }
        db.withTransaction {
            db.habitDao().deleteAll() // cascades to entries, reminders and timers
            db.groupDao().deleteAll()
            groups.forEach { db.groupDao().insert(it) }
            habits.forEach { db.habitDao().insert(it) }
            if (reminders.isNotEmpty()) db.reminderDao().insertAll(reminders)
            entries.forEach { db.entryDao().upsert(it) }
        }
        oldTimers.forEach { TimerNotifier.dismiss(context, it) }
        repository.listener?.onHabitsChanged()
        return summary
    }

    private fun parse(json: String): JSONObject {
        val root = try { JSONObject(json) } catch (e: JSONException) { throw BackupException("That file isn't a Dayrise backup.") }
        if (root.optString("app") != APP) throw BackupException("That file isn't a Dayrise backup.")
        if (root.optInt("version") > VERSION) throw BackupException("That backup is from a newer version of Dayrise.")
        try {
            root.getJSONArray("habits"); root.getJSONArray("entries"); root.getJSONArray("groups"); root.getJSONArray("reminders")
        } catch (e: JSONException) { throw BackupException("That backup is missing data.") }
        return root
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map { getJSONObject(it) }

    private inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: fallback

    companion object {
        const val APP = "dayrise"
        const val VERSION = 1
    }
}
