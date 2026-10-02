package com.repdayrise.app

import com.repdayrise.app.data.model.DaysMask
import com.repdayrise.app.data.model.Habit
import com.repdayrise.app.data.model.HabitType
import com.repdayrise.app.data.model.ScheduleType
import com.repdayrise.app.domain.HabitLogic
import com.repdayrise.app.domain.MoonPhase
import com.repdayrise.app.ui.components.contributionLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

class HabitLogicTest {
    private val logic = HabitLogic(DayOfWeek.MONDAY)
    private val today = LocalDate.of(2026, 9, 15) // Tuesday

    private fun entries(habitId: Long, vararg dates: LocalDate, value: Double = 1.0) =
        mapOf(habitId to dates.associate { it.toEpochDay() to value })

    @Test
    fun dailyStreakCountsConsecutiveDaysAndToleratesTodayUnfinished() {
        val habit = Habit(id = 1, name = "Read", startDate = today.minusDays(10))
        val e = entries(1, today.minusDays(1), today.minusDays(2), today.minusDays(3))
        val stats = logic.stats(habit, e, today)
        assertEquals(3, stats.currentStreak)
        assertEquals(3, stats.bestStreak)
        assertEquals(3, stats.totalDays)
    }

    @Test
    fun dailyStreakBreaksOnMissedScheduledDay() {
        val habit = Habit(id = 1, name = "Read", startDate = today.minusDays(10))
        val e = entries(1, today, today.minusDays(2), today.minusDays(3))
        assertEquals(1, logic.stats(habit, e, today).currentStreak)
        assertEquals(2, logic.stats(habit, e, today).bestStreak)
    }

    @Test
    fun weekdayOnlyHabitSkipsWeekends() {
        val habit = Habit(id = 1, name = "Gym", daysMask = DaysMask.WEEKDAYS, startDate = today.minusDays(20))
        // Mon 14, Fri 11, Thu 10 done; weekend 12-13 unscheduled
        val e = entries(1, today.minusDays(1), today.minusDays(4), today.minusDays(5))
        assertEquals(3, logic.stats(habit, e, today).currentStreak)
    }

    @Test
    fun measurableProgressIsFractional() {
        val habit = Habit(id = 1, name = "Water", type = HabitType.COUNT, goal = 8.0, unit = "glasses")
        val e = entries(1, today, value = 4.0)
        assertEquals(0.5f, logic.dayProgress(listOf(habit), e, today), 0.001f)
        assertTrue(!logic.isCompleted(habit, e, today))
    }

    @Test
    fun weeklyHabitIsDueUntilGoalMet() {
        val habit = Habit(id = 1, name = "Swim", scheduleType = ScheduleType.WEEKLY, timesPerPeriod = 2, startDate = today.minusDays(30))
        val e = entries(1, today.minusDays(1)) // Monday done
        assertTrue(logic.isDue(habit, e, today))
        val e2 = entries(1, today.minusDays(1), today.minusDays(0))
        assertTrue(logic.isDue(habit, e2, today)) // done today, still shown
        val e3 = entries(1, today.minusDays(1), today.minusDays(7)) // wait, different week
        assertTrue(logic.isDue(habit, e3, today))
    }

    @Test
    fun moonPhaseIsInRange() {
        val p = MoonPhase.phase(today)
        assertTrue(p >= 0.0 && p < 1.0)
        // 2026-09-26 is a near-full moon; phase should be around 0.5
        val full = MoonPhase.phase(LocalDate.of(2026, 9, 26))
        assertTrue(full > 0.4 && full < 0.6)
    }

    @Test
    fun graphStartIsAWeekStartFiftyTwoWeeksBack() {
        val start = logic.graphStart(today)
        assertEquals(DayOfWeek.MONDAY, start.dayOfWeek)
        assertEquals(LocalDate.of(2026, 9, 14).minusWeeks(52), start)
    }

    @Test
    fun habitHeatmapReportsFractionsInsideTheRangeOnly() {
        val habit = Habit(id = 1, name = "Water", type = HabitType.COUNT, goal = 8.0, startDate = today.minusDays(400))
        val e = mapOf(1L to mapOf(
            today.toEpochDay() to 4.0,
            today.minusDays(1).toEpochDay() to 12.0,
            today.minusDays(500).toEpochDay() to 8.0,
        ))
        val map = logic.habitHeatmap(habit, e, logic.graphStart(today), today)
        assertEquals(2, map.size)
        assertEquals(0.5f, map[today.toEpochDay()]!!, 0.001f)
        assertEquals(1f, map[today.minusDays(1).toEpochDay()]!!, 0.001f)
    }

    @Test
    fun overallHeatmapAveragesAcrossDueHabits() {
        val a = Habit(id = 1, name = "Read", startDate = today.minusDays(10))
        val b = Habit(id = 2, name = "Walk", startDate = today.minusDays(10))
        val retired = Habit(id = 3, name = "Old", startDate = today.minusDays(10), archived = true)
        val e = mapOf(
            1L to mapOf(today.toEpochDay() to 1.0, today.minusDays(1).toEpochDay() to 1.0),
            2L to mapOf(today.toEpochDay() to 1.0),
            3L to mapOf(today.minusDays(2).toEpochDay() to 1.0),
        )
        val map = logic.overallHeatmap(listOf(a, b, retired), e, logic.graphStart(today), today)
        assertEquals(1f, map[today.toEpochDay()]!!, 0.001f)
        assertEquals(0.5f, map[today.minusDays(1).toEpochDay()]!!, 0.001f)
        assertEquals(null, map[today.minusDays(2).toEpochDay()])
    }

    @Test
    fun contributionLevelsBucketLikeAGithubGraph() {
        assertEquals(0, contributionLevel(0f))
        assertEquals(1, contributionLevel(0.1f))
        assertEquals(2, contributionLevel(0.5f))
        assertEquals(3, contributionLevel(0.9f))
        assertEquals(4, contributionLevel(1f))
    }
}
