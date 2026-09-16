package com.repdayrise.app.domain

import com.repdayrise.app.data.model.DaysMask
import com.repdayrise.app.data.model.Habit
import com.repdayrise.app.data.model.ScheduleType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/** Map of habitId -> (epochDay -> value). */
typealias EntryMap = Map<Long, Map<Long, Double>>

data class HabitStats(
    val currentStreak: Int,
    val bestStreak: Int,
    val totalDays: Int,
    val streakUnit: String, // "days", "weeks", "months"
)

data class DayStatus(
    val date: LocalDate,
    val value: Double,
    val fraction: Float,
    val scheduled: Boolean,
    val completed: Boolean,
)

class HabitLogic(private val weekStart: DayOfWeek = DayOfWeek.MONDAY) {

    fun value(entries: EntryMap, habitId: Long, date: LocalDate): Double =
        entries[habitId]?.get(date.toEpochDay()) ?: 0.0

    fun fraction(habit: Habit, value: Double): Float =
        (value / habit.effectiveGoal).toFloat().coerceIn(0f, 1f)

    fun isCompleted(habit: Habit, value: Double): Boolean = value + 1e-9 >= habit.effectiveGoal

    fun isCompleted(habit: Habit, entries: EntryMap, date: LocalDate): Boolean =
        isCompleted(habit, value(entries, habit.id, date))

    /** For daily habits: whether the weekday is part of the schedule. Weekly/monthly habits can be done any day. */
    fun isScheduledDay(habit: Habit, date: LocalDate): Boolean {
        if (date.isBefore(habit.startDate)) return false
        return when (habit.scheduleType) {
            ScheduleType.DAILY -> DaysMask.contains(habit.daysMask, date.dayOfWeek)
            else -> true
        }
    }

    fun weekStartOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(weekStart))
    fun weekEndOf(date: LocalDate): LocalDate = weekStartOf(date).plusDays(6)
    fun weekDays(anchor: LocalDate): List<LocalDate> {
        val start = weekStartOf(anchor)
        return (0..6).map { start.plusDays(it.toLong()) }
    }

    fun completedCountInPeriod(habit: Habit, entries: EntryMap, date: LocalDate): Int {
        val (start, end) = periodRange(habit, date)
        var count = 0
        var d = start
        while (!d.isAfter(end)) {
            if (isCompleted(habit, entries, d)) count++
            d = d.plusDays(1)
        }
        return count
    }

    fun periodRange(habit: Habit, date: LocalDate): Pair<LocalDate, LocalDate> = when (habit.scheduleType) {
        ScheduleType.WEEKLY -> weekStartOf(date) to weekEndOf(date)
        ScheduleType.MONTHLY -> date.withDayOfMonth(1) to date.withDayOfMonth(date.lengthOfMonth())
        ScheduleType.DAILY -> date to date
    }

    /**
     * Whether the habit should be shown as "due" on [date] in the daily list.
     * Daily habits: scheduled weekdays. Weekly/monthly: due until the period goal is met, or if done that day.
     */
    fun isDue(habit: Habit, entries: EntryMap, date: LocalDate): Boolean {
        if (date.isBefore(habit.startDate)) return false
        return when (habit.scheduleType) {
            ScheduleType.DAILY -> DaysMask.contains(habit.daysMask, date.dayOfWeek)
            else -> {
                if (isCompleted(habit, entries, date)) return true
                completedCountInPeriod(habit, entries, date) < habit.timesPerPeriod
            }
        }
    }

    /** Per-day progress in [0,1] used for the sunrise. */
    fun dayProgress(habits: List<Habit>, entries: EntryMap, date: LocalDate): Float {
        val due = habits.filter { !it.archived && isDue(it, entries, date) }
        if (due.isEmpty()) return 0f
        val total = due.sumOf { fraction(it, value(entries, it.id, date)).toDouble() }
        return (total / due.size).toFloat().coerceIn(0f, 1f)
    }

    fun dayCounts(habits: List<Habit>, entries: EntryMap, date: LocalDate): Pair<Int, Int> {
        val due = habits.filter { !it.archived && isDue(it, entries, date) }
        val done = due.count { isCompleted(it, entries, date) }
        return done to due.size
    }

    fun stats(habit: Habit, entries: EntryMap, today: LocalDate = LocalDate.now()): HabitStats {
        val map = entries[habit.id].orEmpty()
        val totalDays = map.count { (epoch, v) ->
            val d = LocalDate.ofEpochDay(epoch)
            !d.isAfter(today) && isCompleted(habit, v)
        }
        return when (habit.scheduleType) {
            ScheduleType.DAILY -> dailyStats(habit, entries, today, totalDays)
            ScheduleType.WEEKLY -> periodStats(habit, entries, today, totalDays, "weeks")
            ScheduleType.MONTHLY -> periodStats(habit, entries, today, totalDays, "months")
        }
    }

    private fun dailyStats(habit: Habit, entries: EntryMap, today: LocalDate, totalDays: Int): HabitStats {
        val start = habit.startDate
        // Current streak: walk backwards from today. Today counts if done; if not done yet, it doesn't break the streak.
        var current = 0
        var d = today
        var first = true
        while (!d.isBefore(start)) {
            if (isScheduledDay(habit, d)) {
                val done = isCompleted(habit, entries, d)
                if (done) current++ else if (!first) break
                first = false
            }
            d = d.minusDays(1)
            if (ChronoUnit.DAYS.between(d, today) > 3660) break
        }
        // Best streak: scan forward.
        var best = 0
        var run = 0
        var s = start
        while (!s.isAfter(today)) {
            if (isScheduledDay(habit, s)) {
                if (isCompleted(habit, entries, s)) { run++; if (run > best) best = run } else if (s != today) run = 0
            }
            s = s.plusDays(1)
        }
        return HabitStats(current, maxOf(best, current), totalDays, "days")
    }

    private fun periodStats(habit: Habit, entries: EntryMap, today: LocalDate, totalDays: Int, unit: String): HabitStats {
        val periods = mutableListOf<LocalDate>() // period anchors from start to today
        var anchor = periodRange(habit, habit.startDate).first
        val todayAnchor = periodRange(habit, today).first
        var guard = 0
        while (!anchor.isAfter(todayAnchor) && guard++ < 2000) {
            periods.add(anchor)
            anchor = if (habit.scheduleType == ScheduleType.WEEKLY) anchor.plusWeeks(1) else anchor.plusMonths(1)
        }
        val met = periods.map { completedCountInPeriod(habit, entries, it) >= habit.timesPerPeriod }
        var current = 0
        for (i in met.indices.reversed()) {
            if (met[i]) current++ else if (i != met.lastIndex) break
        }
        var best = 0
        var run = 0
        met.forEachIndexed { i, m -> if (m) { run++; best = maxOf(best, run) } else if (i != met.lastIndex) run = 0 }
        return HabitStats(current, maxOf(best, current), totalDays, unit)
    }

    fun dayStatus(habit: Habit, entries: EntryMap, date: LocalDate): DayStatus {
        val v = value(entries, habit.id, date)
        return DayStatus(date, v, fraction(habit, v), isScheduledDay(habit, date), isCompleted(habit, v))
    }

    /** Completion ratio for a month: completed days / expected days (expected = scheduled days so far, or period goal). */
    fun monthCompletion(habit: Habit, entries: EntryMap, month: YearMonth, today: LocalDate = LocalDate.now()): Float {
        val start = month.atDay(1)
        val end = minOf(month.atEndOfMonth(), today)
        if (end.isBefore(start) || end.isBefore(habit.startDate)) return 0f
        return when (habit.scheduleType) {
            ScheduleType.DAILY -> {
                var expected = 0; var done = 0
                var d = maxOf(start, habit.startDate)
                while (!d.isAfter(end)) {
                    if (isScheduledDay(habit, d)) { expected++; if (isCompleted(habit, entries, d)) done++ }
                    d = d.plusDays(1)
                }
                if (expected == 0) 0f else done.toFloat() / expected
            }
            ScheduleType.WEEKLY -> {
                var expected = 0; var done = 0
                var w = weekStartOf(maxOf(start, habit.startDate))
                while (!w.isAfter(end)) {
                    expected += habit.timesPerPeriod
                    done += minOf(completedCountInPeriod(habit, entries, w), habit.timesPerPeriod)
                    w = w.plusWeeks(1)
                }
                if (expected == 0) 0f else done.toFloat() / expected
            }
            ScheduleType.MONTHLY -> {
                val done = minOf(completedCountInPeriod(habit, entries, start), habit.timesPerPeriod)
                done.toFloat() / habit.timesPerPeriod
            }
        }
    }

    /** Count of completed days in an inclusive date range. */
    fun completedInRange(habit: Habit, entries: EntryMap, from: LocalDate, to: LocalDate): Int {
        val map = entries[habit.id].orEmpty()
        return map.count { (epoch, v) ->
            val d = LocalDate.ofEpochDay(epoch)
            !d.isBefore(from) && !d.isAfter(to) && isCompleted(habit, v)
        }
    }

    fun sumInRange(habit: Habit, entries: EntryMap, from: LocalDate, to: LocalDate): Double {
        val map = entries[habit.id].orEmpty()
        return map.entries.sumOf { (epoch, v) ->
            val d = LocalDate.ofEpochDay(epoch)
            if (!d.isBefore(from) && !d.isAfter(to)) v else 0.0
        }
    }
}
