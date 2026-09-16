package com.repdayrise.app.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [HabitEntity::class, EntryEntity::class, GroupEntity::class, ReminderEntity::class, TimerEntity::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class DayriseDatabase : RoomDatabase() {
    abstract fun habitDao(): HabitDao
    abstract fun entryDao(): EntryDao
    abstract fun groupDao(): GroupDao
    abstract fun reminderDao(): ReminderDao
    abstract fun timerDao(): TimerDao

    companion object {
        fun create(context: Context): DayriseDatabase =
            Room.databaseBuilder(context.applicationContext, DayriseDatabase::class.java, "dayrise.db")
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
