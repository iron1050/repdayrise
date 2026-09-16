package com.repdayrise.app.data.health

import com.repdayrise.app.data.HabitRepository
import com.repdayrise.app.data.model.HealthSource
import java.time.LocalDate
import kotlin.math.abs

/** Pulls Health Connect aggregates into habit entries for the last few days. */
class HealthSync(
    private val repository: HabitRepository,
    private val health: HealthConnectManager,
) {
    suspend fun sync(days: Int = 7) {
        if (!health.isAvailable) return
        val habits = repository.getAllHabitsOnce().filter { !it.archived && it.healthSource != HealthSource.NONE }
        if (habits.isEmpty()) return
        val granted = health.grantedPermissions()
        val today = LocalDate.now()
        for (habit in habits) {
            val perm = health.permissionFor(habit.healthSource) ?: continue
            if (perm !in granted) continue
            for (i in 0 until days) {
                val date = today.minusDays(i.toLong())
                if (date.isBefore(habit.startDate)) break
                val value = health.readDay(habit.healthSource, date) ?: continue
                val rounded = Math.round(value * 10.0) / 10.0
                val existing = repository.currentValue(habit.id, date)
                if (abs(existing - rounded) > 0.05) repository.setValue(habit.id, date, rounded)
            }
        }
    }
}
