package com.repdayrise.app.notifications

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.repdayrise.app.AppContainer
import com.repdayrise.app.DayriseApp
import com.repdayrise.app.MainActivity
import com.repdayrise.app.R
import com.repdayrise.app.data.model.ActiveTimer
import com.repdayrise.app.data.model.Habit
import com.repdayrise.app.ui.theme.HabitColors
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * Timer notifications. On Android 16+ this is a Live Update (ProgressStyle, promoted ongoing) with a ticking chronometer,
 * a progress bar toward today's goal and Pause / Finish actions. [sync] is the single entry point: it reads the stored
 * timer and posts, refreshes or removes the notification and its alarms, so the app, the notification actions and the
 * alarms all go through the same path.
 */
object TimerNotifier {
    private const val BASE_ID = 2_000_000
    private const val TICK_MS = 30_000L
    private const val ACTION_TOGGLE = "com.repdayrise.app.TIMER_TOGGLE"
    private const val ACTION_FINISH = "com.repdayrise.app.TIMER_FINISH"
    private const val ACTION_TICK = "com.repdayrise.app.TIMER_TICK"
    private const val ACTION_GOAL = "com.repdayrise.app.TIMER_GOAL"
    private const val EXTRA_HABIT = "habit_id"

    private fun notificationId(habitId: Long) = BASE_ID + habitId.toInt()
    private fun goalNotificationId(habitId: Long) = BASE_ID / 2 + habitId.toInt()

    suspend fun sync(container: AppContainer, habitId: Long) {
        val context = container.context
        val timer = container.repository.getTimer(habitId)
        val habit = container.repository.getHabit(habitId)
        if (timer == null || habit == null) {
            dismiss(context, habitId)
            return
        }
        val earlier = container.repository.currentValue(habitId, timer.date)
        post(context, habit, timer, earlier)
        scheduleAlarms(context, habit, timer, earlier)
    }

    fun dismiss(context: Context, habitId: Long) {
        NotificationManagerCompat.from(context).cancel(notificationId(habitId))
        NotificationManagerCompat.from(context).cancel(goalNotificationId(habitId))
        val alarms = context.getSystemService(AlarmManager::class.java)
        alarms.cancel(actionIntent(context, ACTION_TICK, habitId))
        alarms.cancel(actionIntent(context, ACTION_GOAL, habitId))
    }

    private fun goalMs(habit: Habit) = (habit.effectiveGoal * 60_000).toLong().coerceAtLeast(60_000)

    private fun post(context: Context, habit: Habit, timer: ActiveTimer, earlierMinutes: Double) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val running = timer.running
        val elapsed = timer.elapsedMs()
        val doneMs = (earlierMinutes * 60_000).toLong() + elapsed
        val goal = goalMs(habit)
        val percent = (doneMs.coerceAtMost(goal) * 100 / goal).toInt()
        val minutes = (elapsed / 60000).toInt()
        val seconds = ((elapsed / 1000) % 60).toInt()
        val remainingMin = ceil((goal - doneMs).coerceAtLeast(0) / 60000.0).toInt()
        val text = when {
            doneMs >= goal -> "Goal reached · tap Finish to save"
            running -> "${habit.goalLabel()} goal · $remainingMin min to go"
            else -> "Paused · %d:%02d of %s".format(minutes, seconds, habit.goalLabel())
        }
        val color = HabitColors.of(habit.colorIndex).toArgb()
        val open = PendingIntent.getActivity(
            context, 900 + habit.id.toInt(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_HABIT_ID, habit.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val toggle = actionIntent(context, ACTION_TOGGLE, habit.id)
        val finish = actionIntent(context, ACTION_FINISH, habit.id)
        val toggleLabel = if (running) "Pause" else "Resume"

        if (Build.VERSION.SDK_INT >= 36) {
            val style = Notification.ProgressStyle()
                .setStyledByProgress(false)
                .setProgressSegments(listOf(Notification.ProgressStyle.Segment(100).setColor(color)))
                .setProgress(percent)
            val builder = Notification.Builder(context, Channels.TIMERS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(habit.name)
                .setContentText(text)
                .setContentIntent(open)
                .setColor(color)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setStyle(style)
                .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_notification), toggleLabel, toggle).build())
                .addAction(Notification.Action.Builder(Icon.createWithResource(context, R.drawable.ic_notification), "Finish", finish).build())
            // Live Updates (promoted ongoing notifications) arrived in Android 16 QPR1 (API 36.1).
            if (Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.BAKLAVA_1) {
                builder.setRequestPromotedOngoing(true)
                // While running the chip counts up from the chronometer; a fixed string here would freeze at the first value.
                if (!running) builder.setShortCriticalText("Paused")
            }
            if (running) builder.setWhen(System.currentTimeMillis() - elapsed).setUsesChronometer(true).setShowWhen(true)
            else builder.setShowWhen(false)
            context.getSystemService(NotificationManager::class.java).notify(notificationId(habit.id), builder.build())
        } else {
            val builder = NotificationCompat.Builder(context, Channels.TIMERS)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(habit.name)
                .setContentText(text)
                .setContentIntent(open)
                .setColor(color)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setProgress(100, percent, false)
                .addAction(R.drawable.ic_notification, toggleLabel, toggle)
                .addAction(R.drawable.ic_notification, "Finish", finish)
            if (running) builder.setWhen(System.currentTimeMillis() - elapsed).setUsesChronometer(true).setShowWhen(true)
            else builder.setShowWhen(false)
            NotificationManagerCompat.from(context).notify(notificationId(habit.id), builder.build())
        }
    }

    /** A tick keeps the progress bar moving; a one-off alarm marks the moment the goal is reached. */
    private fun scheduleAlarms(context: Context, habit: Habit, timer: ActiveTimer, earlierMinutes: Double) {
        val alarms = context.getSystemService(AlarmManager::class.java)
        val tick = actionIntent(context, ACTION_TICK, habit.id)
        val goalAlarm = actionIntent(context, ACTION_GOAL, habit.id)
        alarms.cancel(tick)
        alarms.cancel(goalAlarm)
        if (!timer.running) return
        val now = System.currentTimeMillis()
        val remaining = goalMs(habit) - ((earlierMinutes * 60_000).toLong() + timer.elapsedMs())
        if (remaining > TICK_MS) alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + TICK_MS, tick)
        if (remaining > 0) {
            if (alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + remaining, goalAlarm)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, now + remaining, goalAlarm)
        }
    }

    private fun showGoalReached(context: Context, habit: Habit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val open = PendingIntent.getActivity(
            context, 900 + habit.id.toInt(),
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_HABIT_ID, habit.id)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(context, Channels.REMINDERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${habit.name} · goal reached")
            .setContentText("You hit ${habit.goalLabel()}. Finish to bank it, or keep going.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(R.drawable.ic_notification, "Finish", actionIntent(context, ACTION_FINISH, habit.id))
            .build()
        NotificationManagerCompat.from(context).notify(goalNotificationId(habit.id), n)
    }

    private fun actionIntent(context: Context, action: String, habitId: Long): PendingIntent = PendingIntent.getBroadcast(
        context, action.hashCode() xor habitId.toInt(),
        Intent(context, TimerReceiver::class.java).setAction(action).putExtra(EXTRA_HABIT, habitId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    internal fun handle(context: Context, intent: Intent, pending: BroadcastReceiver.PendingResult) {
        val container = (context.applicationContext as DayriseApp).container
        val habitId = intent.getLongExtra(EXTRA_HABIT, -1)
        container.appScope.launch {
            try {
                val repo = container.repository
                val habit = repo.getHabit(habitId)
                when (intent.action) {
                    ACTION_TOGGLE -> {
                        val t = repo.getTimer(habitId)
                        if (habit != null && t != null) {
                            if (t.running) repo.pauseTimer(habitId) else repo.startTimer(habit, t.date)
                        }
                        sync(container, habitId)
                    }
                    ACTION_FINISH -> {
                        if (habit != null) repo.finishTimer(habit)
                        NotificationManagerCompat.from(context).cancel(goalNotificationId(habitId))
                        sync(container, habitId)
                    }
                    ACTION_TICK -> sync(container, habitId)
                    ACTION_GOAL -> if (habit != null && repo.getTimer(habitId)?.running == true) {
                        sync(container, habitId)
                        showGoalReached(context, habit)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}

class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TimerNotifier.handle(context, intent, goAsync())
    }
}
