package com.repdayrise.app.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.repdayrise.app.DayriseApp
import com.repdayrise.app.MainActivity
import com.repdayrise.app.R
import com.repdayrise.app.data.model.DaysMask
import com.repdayrise.app.data.model.Habit
import com.repdayrise.app.data.model.Reminder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

object Channels {
    const val REMINDERS = "reminders"
    const val TIMERS = "timers"

    fun ensure(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(REMINDERS, context.getString(R.string.notification_channel_reminders), NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Gentle nudges for the habits you scheduled."
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(TIMERS, context.getString(R.string.notification_channel_timers), NotificationManager.IMPORTANCE_LOW).apply {
                description = "Running habit timers."
                setSound(null, null)
            }
        )
    }
}

class ReminderScheduler(private val context: Context) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)

    fun canScheduleExact(): Boolean = alarmManager.canScheduleExactAlarms()

    /** Reschedules the next occurrence of every enabled reminder for active habits. */
    fun scheduleAll(habits: List<Habit>) {
        for (habit in habits) {
            for (reminder in habit.reminders) {
                if (habit.archived || !reminder.enabled) cancel(reminder.id) else schedule(habit, reminder)
            }
        }
    }

    fun schedule(habit: Habit, reminder: Reminder, after: LocalDateTime = LocalDateTime.now()) {
        val next = nextTrigger(reminder, after) ?: return
        val trigger = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val pi = pendingIntent(habit.id, reminder.id)
        if (canScheduleExact()) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        } else {
            alarmManager.setWindow(AlarmManager.RTC_WAKEUP, trigger, 10 * 60_000L, pi)
        }
    }

    fun cancel(reminderId: Long) {
        alarmManager.cancel(pendingIntent(0, reminderId))
    }

    private fun pendingIntent(habitId: Long, reminderId: Long): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ReminderReceiver.ACTION_FIRE
            putExtra(ReminderReceiver.EXTRA_HABIT_ID, habitId)
            putExtra(ReminderReceiver.EXTRA_REMINDER_ID, reminderId)
        }
        return PendingIntent.getBroadcast(
            context, reminderId.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        fun nextTrigger(reminder: Reminder, after: LocalDateTime): LocalDateTime? {
            if (DaysMask.count(reminder.daysMask) == 0) return null
            val time = LocalTime.of(reminder.hour, reminder.minute)
            var date = after.toLocalDate()
            for (i in 0..7) {
                val candidate = LocalDateTime.of(date, time)
                if (candidate.isAfter(after) && DaysMask.contains(reminder.daysMask, date.dayOfWeek)) return candidate
                date = date.plusDays(1)
            }
            return null
        }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as DayriseApp
        val container = app.container
        val habitId = intent.getLongExtra(EXTRA_HABIT_ID, -1)
        val reminderId = intent.getLongExtra(EXTRA_REMINDER_ID, -1)
        val pending = goAsync()
        container.appScope.launch {
            try {
                when (intent.action) {
                    ACTION_FIRE -> {
                        val habit = container.repository.getHabit(habitId) ?: return@launch
                        val reminder = habit.reminders.firstOrNull { it.id == reminderId }
                        val today = LocalDate.now()
                        val entries = container.repository.getEntriesOnce()
                        val done = container.logic().isCompleted(habit, entries, today)
                        if (!habit.archived && !done) showReminder(context, habit)
                        if (reminder != null && !habit.archived) container.reminderScheduler.schedule(habit, reminder)
                    }
                    ACTION_COMPLETE -> {
                        val habit = container.repository.getHabit(habitId) ?: return@launch
                        container.repository.setValue(habit.id, LocalDate.now(), habit.effectiveGoal)
                        NotificationManagerCompat.from(context).cancel(notificationId(habitId))
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }

    private fun showReminder(context: Context, habit: Habit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(
            context, habit.id.toInt(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_HABIT_ID, habit.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val complete = PendingIntent.getBroadcast(
            context, (habit.id * 31 + 7).toInt(),
            Intent(context, ReminderReceiver::class.java).apply {
                action = ACTION_COMPLETE
                putExtra(EXTRA_HABIT_ID, habit.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val body = when {
            habit.isMeasurable -> "Goal: ${habit.goalLabel()}. Let's raise the sun a little."
            else -> "A small step toward today's sunrise."
        }
        val notification = NotificationCompat.Builder(context, Channels.REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(habit.name)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(R.drawable.ic_notification, "Mark done", complete)
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(habit.id), notification)
    }

    companion object {
        const val ACTION_FIRE = "com.repdayrise.app.REMINDER"
        const val ACTION_COMPLETE = "com.repdayrise.app.COMPLETE"
        const val EXTRA_HABIT_ID = "habit_id"
        const val EXTRA_REMINDER_ID = "reminder_id"
        fun notificationId(habitId: Long) = 1000 + habitId.toInt()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = (context.applicationContext as DayriseApp).container
        val pending = goAsync()
        container.appScope.launch {
            try {
                container.reminderScheduler.scheduleAll(container.repository.getAllHabitsOnce())
            } finally {
                pending.finish()
            }
        }
    }
}

/** Timer notifications. Uses Android 16 Live Updates (ProgressStyle, promoted ongoing) where available. */
object TimerNotifier {
    private const val ID = 500

    fun show(context: Context, habit: Habit, elapsedMs: Long, running: Boolean) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(
            context, 900,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_HABIT_ID, habit.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val goalMs = (habit.goal * 60_000).toLong().coerceAtLeast(60_000)
        val minutes = (elapsedMs / 60000).toInt()
        val seconds = ((elapsedMs / 1000) % 60).toInt()
        val text = if (running) "%d:%02d · goal %s".format(minutes, seconds, habit.goalLabel()) else "Paused · %d:%02d".format(minutes, seconds)

        if (Build.VERSION.SDK_INT >= 36) {
            val style = Notification.ProgressStyle()
                .setProgress((elapsedMs.coerceAtMost(goalMs) * 100 / goalMs).toInt())
                .setProgressSegments(listOf(Notification.ProgressStyle.Segment(100)))
            val builder = Notification.Builder(context, Channels.TIMERS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(habit.name)
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(running)
                .setOnlyAlertOnce(true)
                .setStyle(style)
            // Live Updates (promoted ongoing notifications) arrived in Android 16 QPR1 (API 36.1).
            if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) builder.setRequestPromotedOngoing(true)
            if (running) {
                builder.setWhen(System.currentTimeMillis() - elapsedMs).setUsesChronometer(true).setShowWhen(true)
            }
            context.getSystemService(NotificationManager::class.java).notify(ID, builder.build())
        } else {
            val builder = NotificationCompat.Builder(context, Channels.TIMERS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(habit.name)
                .setContentText(text)
                .setContentIntent(open)
                .setOngoing(running)
                .setOnlyAlertOnce(true)
                .setProgress(100, (elapsedMs.coerceAtMost(goalMs) * 100 / goalMs).toInt(), false)
            if (running) builder.setWhen(System.currentTimeMillis() - elapsedMs).setUsesChronometer(true).setShowWhen(true)
            NotificationManagerCompat.from(context).notify(ID, builder.build())
        }
    }

    fun dismiss(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID)
    }
}
