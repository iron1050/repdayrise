package com.repdayrise.app.data.model

import java.time.DayOfWeek
import java.time.LocalDate

enum class HabitType { CHECK, COUNT, TIMER }

enum class ScheduleType { DAILY, WEEKLY, MONTHLY }

enum class HealthSource(val label: String, val unit: String, val defaultGoal: Double) {
    NONE("None", "", 1.0),
    STEPS("Steps", "steps", 8000.0),
    DISTANCE("Walking + running", "km", 5.0),
    WATER("Water", "ml", 2000.0),
    SLEEP("Sleep", "hours", 8.0),
    EXERCISE("Exercise", "min", 30.0),
    CALORIES("Active calories", "kcal", 400.0),
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class AppIcon(val alias: String, val label: String) {
    SUNRISE("IconSunrise", "Sunrise"),
    NIGHT("IconNight", "Night"),
    MONO("IconMono", "Minimal"),
}

/** Bitmask helpers for weekday schedules: bit 0 = Monday … bit 6 = Sunday. */
object DaysMask {
    const val ALL = 0b1111111
    const val WEEKDAYS = 0b0011111
    const val WEEKEND = 0b1100000

    fun contains(mask: Int, day: DayOfWeek): Boolean = (mask shr (day.value - 1)) and 1 == 1
    fun toggle(mask: Int, day: DayOfWeek): Int = mask xor (1 shl (day.value - 1))
    fun with(mask: Int, day: DayOfWeek): Int = mask or (1 shl (day.value - 1))
    fun count(mask: Int): Int = Integer.bitCount(mask and ALL)
    fun days(mask: Int): List<DayOfWeek> = DayOfWeek.entries.filter { contains(mask, it) }
}

data class Reminder(
    val id: Long = 0,
    val habitId: Long = 0,
    val hour: Int,
    val minute: Int,
    val daysMask: Int = DaysMask.ALL,
    val enabled: Boolean = true,
) {
    fun timeLabel(): String {
        val h12 = when (hour % 12) { 0 -> 12; else -> hour % 12 }
        val suffix = if (hour < 12) "AM" else "PM"
        return "%d:%02d %s".format(h12, minute, suffix)
    }
}

data class HabitGroup(
    val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0,
    val collapsed: Boolean = false,
)

data class Habit(
    val id: Long = 0,
    val name: String,
    val icon: String = "sun",
    val colorIndex: Int = 0,
    val type: HabitType = HabitType.CHECK,
    val goal: Double = 1.0,
    val unit: String = "",
    val scheduleType: ScheduleType = ScheduleType.DAILY,
    val daysMask: Int = DaysMask.ALL,
    val timesPerPeriod: Int = 3,
    val groupId: Long? = null,
    val startDate: LocalDate = LocalDate.now(),
    val createdAt: Long = System.currentTimeMillis(),
    val archived: Boolean = false,
    val archivedAt: Long? = null,
    val sortOrder: Int = 0,
    val healthSource: HealthSource = HealthSource.NONE,
    val note: String = "",
    val reminders: List<Reminder> = emptyList(),
) {
    val isMeasurable: Boolean get() = type != HabitType.CHECK
    val effectiveGoal: Double get() = if (type == HabitType.CHECK) 1.0 else goal.coerceAtLeast(0.0001)

    fun scheduleLabel(): String = when (scheduleType) {
        ScheduleType.DAILY -> when (daysMask and DaysMask.ALL) {
            DaysMask.ALL -> "Every day"
            DaysMask.WEEKDAYS -> "Weekdays"
            DaysMask.WEEKEND -> "Weekends"
            else -> DaysMask.days(daysMask).joinToString(" ") { it.name.take(3).lowercase().replaceFirstChar(Char::uppercase) }
        }
        ScheduleType.WEEKLY -> "$timesPerPeriod× a week"
        ScheduleType.MONTHLY -> "$timesPerPeriod× a month"
    }

    fun goalLabel(): String = when (type) {
        HabitType.CHECK -> "Complete"
        HabitType.COUNT -> "${formatValue(goal)} ${unit.ifBlank { "times" }}"
        HabitType.TIMER -> formatDuration(goal)
    }
}

/** A single day's record for a habit. Value is in the habit's unit (minutes for timers, 1 for checks). */
data class Entry(
    val habitId: Long,
    val date: LocalDate,
    val value: Double,
)

data class ActiveTimer(
    val habitId: Long,
    val date: LocalDate,
    val startedAt: Long,
    val accumulatedMs: Long,
    val running: Boolean,
) {
    fun elapsedMs(now: Long = System.currentTimeMillis()): Long =
        accumulatedMs + if (running) (now - startedAt).coerceAtLeast(0) else 0
}

fun formatValue(v: Double): String =
    if (v == Math.floor(v) && v < 1e9) v.toLong().toString() else String.format(java.util.Locale.US, "%.1f", v)

/** Formats minutes as h/m string. */
fun formatDuration(minutes: Double): String {
    val total = minutes.toInt()
    val h = total / 60
    val m = total % 60
    return when {
        h > 0 && m > 0 -> "${h}h ${m}m"
        h > 0 -> "${h}h"
        else -> "${m}m"
    }
}
