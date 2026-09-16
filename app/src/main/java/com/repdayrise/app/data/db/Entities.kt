package com.repdayrise.app.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.repdayrise.app.data.model.HabitType
import com.repdayrise.app.data.model.HealthSource
import com.repdayrise.app.data.model.ScheduleType

@Entity(tableName = "habits")
data class HabitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val icon: String,
    val colorIndex: Int,
    val type: HabitType,
    val goal: Double,
    val unit: String,
    val scheduleType: ScheduleType,
    val daysMask: Int,
    val timesPerPeriod: Int,
    val groupId: Long?,
    val startDate: Long,
    val createdAt: Long,
    val archived: Boolean,
    val archivedAt: Long?,
    val sortOrder: Int,
    val healthSource: HealthSource,
    val note: String,
)

@Entity(
    tableName = "entries",
    primaryKeys = ["habitId", "date"],
    indices = [Index("date")],
    foreignKeys = [
        ForeignKey(
            entity = HabitEntity::class,
            parentColumns = ["id"],
            childColumns = ["habitId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class EntryEntity(
    val habitId: Long,
    val date: Long,
    val value: Double,
    val updatedAt: Long,
)

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val sortOrder: Int,
    val collapsed: Boolean,
)

@Entity(
    tableName = "reminders",
    indices = [Index("habitId")],
    foreignKeys = [
        ForeignKey(
            entity = HabitEntity::class,
            parentColumns = ["id"],
            childColumns = ["habitId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class ReminderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val habitId: Long,
    val hour: Int,
    val minute: Int,
    val daysMask: Int,
    val enabled: Boolean,
)

@Entity(
    tableName = "timers",
    foreignKeys = [
        ForeignKey(
            entity = HabitEntity::class,
            parentColumns = ["id"],
            childColumns = ["habitId"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
)
data class TimerEntity(
    @PrimaryKey val habitId: Long,
    val date: Long,
    val startedAt: Long,
    val accumulatedMs: Long,
    val running: Boolean,
)

class Converters {
    @TypeConverter fun habitTypeToString(v: HabitType): String = v.name
    @TypeConverter fun stringToHabitType(v: String): HabitType = HabitType.valueOf(v)
    @TypeConverter fun scheduleTypeToString(v: ScheduleType): String = v.name
    @TypeConverter fun stringToScheduleType(v: String): ScheduleType = ScheduleType.valueOf(v)
    @TypeConverter fun healthToString(v: HealthSource): String = v.name
    @TypeConverter fun stringToHealth(v: String): HealthSource = runCatching { HealthSource.valueOf(v) }.getOrDefault(HealthSource.NONE)
}
