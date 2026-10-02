package com.repdayrise.app.data.sharing

import com.repdayrise.app.data.model.Habit
import com.repdayrise.app.data.model.HabitType
import com.repdayrise.app.data.model.ScheduleType
import com.repdayrise.app.domain.EntryMap
import kotlinx.serialization.Serializable
import java.time.LocalDate

/** How many days of history a shared snapshot carries: enough for the year graph and its streaks. */
const val SNAPSHOT_DAYS = 400L

/** A habit as a partner sees it. Notes, reminders and groups stay on the owner's phone. */
@Serializable
data class SharedHabit(
    val id: Long,
    val name: String,
    val icon: String = "sun",
    val colorIndex: Int = 0,
    val type: String = HabitType.CHECK.name,
    val goal: Double = 1.0,
    val unit: String = "",
    val scheduleType: String = ScheduleType.DAILY.name,
    val daysMask: Int = 0b1111111,
    val timesPerPeriod: Int = 1,
    val startDate: Long = 0,
) {
    fun toHabit(): Habit = Habit(
        id = id, name = name, icon = icon, colorIndex = colorIndex,
        type = runCatching { HabitType.valueOf(type) }.getOrDefault(HabitType.CHECK),
        goal = goal, unit = unit,
        scheduleType = runCatching { ScheduleType.valueOf(scheduleType) }.getOrDefault(ScheduleType.DAILY),
        daysMask = daysMask, timesPerPeriod = timesPerPeriod.coerceAtLeast(1),
        startDate = LocalDate.ofEpochDay(startDate),
    )
}

fun Habit.toShared(): SharedHabit = SharedHabit(
    id = id, name = name, icon = icon, colorIndex = colorIndex, type = type.name, goal = goal, unit = unit,
    scheduleType = scheduleType.name, daysMask = daysMask, timesPerPeriod = timesPerPeriod, startDate = startDate.toEpochDay(),
)

/** Everything a partner needs to draw the owner's list: habits plus habitId -> (epochDay -> value). */
@Serializable
data class Snapshot(
    val schema: Int = 1,
    val weekStartsMonday: Boolean = true,
    val habits: List<SharedHabit> = emptyList(),
    val entries: Map<Long, Map<Long, Double>> = emptyMap(),
) {
    companion object {
        /** Builds the snapshot to publish: active, non-excluded habits and their recent entries. */
        fun build(habits: List<Habit>, entries: EntryMap, excluded: Set<Long>, weekStartsMonday: Boolean, today: LocalDate): Snapshot {
            val shared = habits.filter { !it.archived && it.id !in excluded }
            val cutoff = today.minusDays(SNAPSHOT_DAYS).toEpochDay()
            val last = today.toEpochDay()
            return Snapshot(
                weekStartsMonday = weekStartsMonday,
                habits = shared.map { it.toShared() },
                entries = shared.associate { h ->
                    h.id to entries[h.id].orEmpty().filter { (day, v) -> day in cutoff..last && v > 0.0 }.toSortedMap()
                }.filterValues { it.isNotEmpty() },
            )
        }
    }
}

/** Someone following the owner's list. */
@Serializable
data class Partner(
    val id: String,
    val name: String,
    val joinedAt: Long = 0,
    val lastSeenAt: Long? = null,
)

/** The list this phone publishes. */
@Serializable
data class Outgoing(
    val serverUrl: String,
    val shareId: String,
    val token: String,
    val code: String,
    val excludedHabitIds: Set<Long> = emptySet(),
    val partners: List<Partner> = emptyList(),
    val lastSyncedAt: Long = 0,
    val lastPushedDigest: String = "",
    val lastError: String? = null,
)

/** A list this phone subscribes to, with the last snapshot it fetched. */
@Serializable
data class Following(
    val serverUrl: String,
    val shareId: String,
    val token: String,
    val ownerName: String,
    val version: Int = 0,
    val updatedAt: Long = 0,
    val fetchedAt: Long = 0,
    val snapshot: Snapshot? = null,
    /** The owner removed us or stopped sharing; the cached snapshot is all that's left. */
    val ended: Boolean = false,
)

@Serializable
data class SharingState(
    /** Overrides the address baked in at build time. Blank means use the default. */
    val serverUrl: String = "",
    val displayName: String = "",
    val outgoing: Outgoing? = null,
    val following: List<Following> = emptyList(),
)

// Wire formats

@Serializable internal data class NameBody(val name: String)
@Serializable internal data class CreateShareResponse(val id: String, val token: String, val code: String)
@Serializable internal data class PublishBody(val name: String, val snapshot: Snapshot)
@Serializable internal data class StatusResponse(val code: String, val subscribers: List<Partner> = emptyList())
@Serializable internal data class CodeResponse(val code: String)
@Serializable internal data class JoinBody(val code: String, val name: String)
@Serializable internal data class JoinResponse(val token: String, val shareId: String, val ownerName: String)
@Serializable internal data class FeedResponse(val ownerName: String, val version: Int, val updatedAt: Long, val snapshot: Snapshot? = null)
@Serializable internal data class ErrorBody(val error: String = "", val message: String = "")
