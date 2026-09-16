package com.repdayrise.app.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface HabitDao {
    @Query("SELECT * FROM habits ORDER BY sortOrder ASC, createdAt ASC")
    fun observeAll(): Flow<List<HabitEntity>>

    @Query("SELECT * FROM habits ORDER BY sortOrder ASC, createdAt ASC")
    suspend fun getAll(): List<HabitEntity>

    @Query("SELECT * FROM habits WHERE id = :id")
    suspend fun getById(id: Long): HabitEntity?

    @Query("SELECT COALESCE(MAX(sortOrder), -1) + 1 FROM habits")
    suspend fun nextSortOrder(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(habit: HabitEntity): Long

    @Update
    suspend fun update(habit: HabitEntity)

    @Query("UPDATE habits SET sortOrder = :order WHERE id = :id")
    suspend fun updateSortOrder(id: Long, order: Int)

    @Query("UPDATE habits SET archived = :archived, archivedAt = :archivedAt WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean, archivedAt: Long?)

    @Query("DELETE FROM habits WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface EntryDao {
    @Query("SELECT * FROM entries")
    fun observeAll(): Flow<List<EntryEntity>>

    @Query("SELECT * FROM entries")
    suspend fun getAll(): List<EntryEntity>

    @Query("SELECT * FROM entries WHERE habitId = :habitId AND date = :date")
    suspend fun get(habitId: Long, date: Long): EntryEntity?

    @Query("SELECT * FROM entries WHERE date = :date")
    suspend fun getForDate(date: Long): List<EntryEntity>

    @Upsert
    suspend fun upsert(entry: EntryEntity)

    @Query("DELETE FROM entries WHERE habitId = :habitId AND date = :date")
    suspend fun delete(habitId: Long, date: Long)

    @Query("DELETE FROM entries WHERE habitId = :habitId")
    suspend fun deleteForHabit(habitId: Long)
}

@Dao
interface GroupDao {
    @Query("SELECT * FROM groups ORDER BY sortOrder ASC, id ASC")
    fun observeAll(): Flow<List<GroupEntity>>

    @Query("SELECT * FROM groups ORDER BY sortOrder ASC, id ASC")
    suspend fun getAll(): List<GroupEntity>

    @Insert
    suspend fun insert(group: GroupEntity): Long

    @Update
    suspend fun update(group: GroupEntity)

    @Query("UPDATE groups SET collapsed = :collapsed WHERE id = :id")
    suspend fun setCollapsed(id: Long, collapsed: Boolean)

    @Query("DELETE FROM groups WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("UPDATE habits SET groupId = NULL WHERE groupId = :groupId")
    suspend fun detachHabits(groupId: Long)
}

@Dao
interface ReminderDao {
    @Query("SELECT * FROM reminders ORDER BY hour, minute")
    fun observeAll(): Flow<List<ReminderEntity>>

    @Query("SELECT * FROM reminders ORDER BY hour, minute")
    suspend fun getAll(): List<ReminderEntity>

    @Query("SELECT * FROM reminders WHERE id = :id")
    suspend fun getById(id: Long): ReminderEntity?

    @Insert
    suspend fun insertAll(reminders: List<ReminderEntity>)

    @Query("DELETE FROM reminders WHERE habitId = :habitId")
    suspend fun deleteForHabit(habitId: Long)

    @Transaction
    suspend fun replaceForHabit(habitId: Long, reminders: List<ReminderEntity>) {
        deleteForHabit(habitId)
        if (reminders.isNotEmpty()) insertAll(reminders.map { it.copy(id = 0, habitId = habitId) })
    }
}

@Dao
interface TimerDao {
    @Query("SELECT * FROM timers")
    fun observeAll(): Flow<List<TimerEntity>>

    @Query("SELECT * FROM timers WHERE habitId = :habitId")
    suspend fun get(habitId: Long): TimerEntity?

    @Upsert
    suspend fun upsert(timer: TimerEntity)

    @Delete
    suspend fun delete(timer: TimerEntity)

    @Query("DELETE FROM timers WHERE habitId = :habitId")
    suspend fun delete(habitId: Long)
}
