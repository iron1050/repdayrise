package com.repdayrise.app.data.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HydrationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.repdayrise.app.data.model.HealthSource
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

class HealthConnectManager(private val context: Context) {

    val isAvailable: Boolean
        get() = HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    private val client: HealthConnectClient? by lazy {
        if (isAvailable) runCatching { HealthConnectClient.getOrCreate(context) }.getOrNull() else null
    }

    fun permissionFor(source: HealthSource): String? = when (source) {
        HealthSource.NONE -> null
        HealthSource.STEPS -> HealthPermission.getReadPermission(StepsRecord::class)
        HealthSource.DISTANCE -> HealthPermission.getReadPermission(DistanceRecord::class)
        HealthSource.WATER -> HealthPermission.getReadPermission(HydrationRecord::class)
        HealthSource.SLEEP -> HealthPermission.getReadPermission(SleepSessionRecord::class)
        HealthSource.EXERCISE -> HealthPermission.getReadPermission(ExerciseSessionRecord::class)
        HealthSource.CALORIES -> HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class)
    }

    val allPermissions: Set<String>
        get() = HealthSource.entries.mapNotNull { permissionFor(it) }.toSet()

    suspend fun grantedPermissions(): Set<String> =
        runCatching { client?.permissionController?.getGrantedPermissions() ?: emptySet() }.getOrDefault(emptySet())

    suspend fun hasPermission(source: HealthSource): Boolean {
        val p = permissionFor(source) ?: return false
        return p in grantedPermissions()
    }

    /**
     * Reads the day's aggregate for [source]. Sleep is attributed to the day the user wakes up
     * (sessions ending between 18:00 the previous evening and 18:00 that day).
     */
    suspend fun readDay(source: HealthSource, date: LocalDate): Double? {
        val c = client ?: return null
        val zone = java.time.ZoneId.systemDefault()
        val (start, end) = if (source == HealthSource.SLEEP) {
            LocalDateTime.of(date.minusDays(1), LocalTime.of(18, 0)) to LocalDateTime.of(date, LocalTime.of(18, 0))
        } else {
            date.atStartOfDay() to date.plusDays(1).atStartOfDay()
        }
        val filter = TimeRangeFilter.between(start.atZone(zone).toInstant(), end.atZone(zone).toInstant())
        return runCatching {
            when (source) {
                HealthSource.NONE -> null
                HealthSource.STEPS -> c.aggregate(AggregateRequest(setOf(StepsRecord.COUNT_TOTAL), filter))[StepsRecord.COUNT_TOTAL]?.toDouble()
                HealthSource.DISTANCE -> c.aggregate(AggregateRequest(setOf(DistanceRecord.DISTANCE_TOTAL), filter))[DistanceRecord.DISTANCE_TOTAL]?.inKilometers
                HealthSource.WATER -> c.aggregate(AggregateRequest(setOf(HydrationRecord.VOLUME_TOTAL), filter))[HydrationRecord.VOLUME_TOTAL]?.inMilliliters
                HealthSource.SLEEP -> c.aggregate(AggregateRequest(setOf(SleepSessionRecord.SLEEP_DURATION_TOTAL), filter))[SleepSessionRecord.SLEEP_DURATION_TOTAL]?.toMinutes()?.div(60.0)
                HealthSource.EXERCISE -> c.aggregate(AggregateRequest(setOf(ExerciseSessionRecord.EXERCISE_DURATION_TOTAL), filter))[ExerciseSessionRecord.EXERCISE_DURATION_TOTAL]?.toMinutes()?.toDouble()
                HealthSource.CALORIES -> c.aggregate(AggregateRequest(setOf(ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL), filter))[ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL]?.inKilocalories
            }
        }.getOrNull()
    }
}
