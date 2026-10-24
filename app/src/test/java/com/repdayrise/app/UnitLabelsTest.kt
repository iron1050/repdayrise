package com.repdayrise.app

import com.repdayrise.app.data.model.amountLabel
import com.repdayrise.app.data.model.countLabel
import com.repdayrise.app.data.model.singularUnit
import com.repdayrise.app.domain.HabitStats
import org.junit.Assert.assertEquals
import org.junit.Test

class UnitLabelsTest {
    @Test
    fun unitsDropTheirPluralForOne() {
        assertEquals("1 session", amountLabel(1.0, "sessions"))
        assertEquals("3 sessions", amountLabel(3.0, "sessions"))
        assertEquals("1 glass", amountLabel(1.0, "glasses"))
        assertEquals("1 step", amountLabel(1.0, "steps"))
        assertEquals("1 km", amountLabel(1.0, "km"))
        assertEquals("pass", singularUnit("pass"))
    }

    @Test
    fun countLabelsAgreeWithTheNumber() {
        assertEquals("1 week", countLabel(1, "weeks"))
        assertEquals("4 weeks", countLabel(4, "weeks"))
        assertEquals("1 day", countLabel(1, "days"))
    }

    @Test
    fun streaksOfDifferentUnitsCompareInDays() {
        val daily = HabitStats(0, 13, 0, "days")
        val weekly = HabitStats(0, 4, 0, "weeks")
        assertEquals("week", weekly.streakNoun)
        assertEquals(28, weekly.bestStreakDays)
        assert(weekly.bestStreakDays > daily.bestStreakDays)
    }
}
