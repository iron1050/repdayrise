package com.repdayrise.app.notifications

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.repdayrise.app.AppContainer
import com.repdayrise.app.MainActivity
import com.repdayrise.app.R
import com.repdayrise.app.container
import com.repdayrise.app.data.model.ActiveTimer
import com.repdayrise.app.data.model.Habit
import com.repdayrise.app.data.model.formatDuration
import com.repdayrise.app.domain.EntryMap
import com.repdayrise.app.ui.theme.HabitColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Running habit timers as Android 16 Live Updates: a promoted, ongoing ProgressStyle notification whose
 * status bar chip counts down to the day's goal, then counts the overtime. Older releases get the same
 * notification without the promotion. Notifications mirror the timers table, so callers only change timers.
 */
object TimerLiveUpdates {
    private const val TICK_MS = 30_000L
    private const val PROGRESS_MAX = 1000
    private const val ID_BASE = 2000
    private const val ALERT_ID_BASE = 3000

    const val ACTION_PAUSE = "com.repdayrise.app.TIMER_PAUSE"
    const val ACTION_RESUME = "com.repdayrise.app.TIMER_RESUME"
    const val ACTION_FINISH = "com.repdayrise.app.TIMER_FINISH"
    const val ACTION_GOAL = "com.repdayrise.app.TIMER_GOAL"
    const val EXTRA_HABIT_ID = "habit_id"

    private val scheduledGoals = HashMap<Long, Long>()
    private val nudge = MutableStateFlow(0)

    /** Re-posts now, e.g. once notification permission is granted while a timer is already running. */
    fun refresh() { nudge.value++ }

    fun notificationId(habitId: Long) = ID_BASE + habitId.toInt()
    private fun alertId(habitId: Long) = ALERT_ID_BASE + habitId.toInt()

    /** Keeps notifications in step with the timers for as long as the process lives; ticks the progress bar while any run. */
    fun follow(container: AppContainer) {
        val repo = container.repository
        container.appScope.launch {
            combine(repo.timers, repo.activeHabits, repo.entries, nudge) { t, h, e, _ -> Triple(t, h, e) }.collectLatest { (timers, habits, entries) ->
                sync(container, timers, habits, entries)
                while (timers.any { it.running }) {
                    delay(TICK_MS)
                    sync(container, timers, habits, entries)
                }
            }
        }
    }

    private fun sync(container: AppContainer, timers: List<ActiveTimer>, habits: List<Habit>, entries: EntryMap) {
        val context = container.context
        val nm = context.getSystemService(NotificationManager::class.java)
        val live = HashSet<Int>()
        val now = System.currentTimeMillis()
        for (timer in timers) {
            val habit = habits.firstOrNull { it.id == timer.habitId } ?: continue
            val session = Session(habit, timer, container.logic().value(entries, habit.id, timer.date), now)
            post(context, session)
            scheduleGoal(context, habit.id, if (timer.running && session.remainingMs > 0) now + session.remainingMs else null)
            live += notificationId(habit.id)
        }
        // Clear notifications for timers that were finished or discarded, including ones left by an earlier process.
        for (sbn in nm.activeNotifications) {
            if (sbn.id in ID_BASE until ALERT_ID_BASE && sbn.id !in live) {
                nm.cancel(sbn.id)
                scheduleGoal(context, (sbn.id - ID_BASE).toLong(), null)
            }
        }
    }

    /** Where a timer stands against the day's goal, counting minutes already logged that day. */
    private class Session(val habit: Habit, val timer: ActiveTimer, loggedMinutes: Double, now: Long) {
        val goalMs = (habit.effectiveGoal * 60_000).toLong().coerceAtLeast(60_000)
        val doneMs = (loggedMinutes * 60_000).toLong() + timer.elapsedMs(now)
        val remainingMs = goalMs - doneMs
        val fraction = (doneMs.toDouble() / goalMs).coerceIn(0.0, 1.0)
    }

    private fun post(context: Context, s: Session) {
        if (!canNotify(context)) return
        val habit = s.habit
        val color = HabitColors.of(habit.colorIndex).toArgb()
        val now = System.currentTimeMillis()
        val reached = s.remainingMs <= 0
        val text = when {
            !s.timer.running && reached -> "Paused · goal reached"
            !s.timer.running -> "Paused · ${clock(s.remainingMs)} left of ${habit.goalLabel()}"
            reached -> "Goal reached · finish to log it"
            else -> "${habit.goalLabel()} goal · ${formatDuration(s.doneMs / 60_000.0)} done"
        }
        val style = NotificationCompat.ProgressStyle()
            .setProgressSegments(listOf(NotificationCompat.ProgressStyle.Segment(PROGRESS_MAX).setColor(color)))
            .setProgress((s.fraction * PROGRESS_MAX).toInt())
        val builder = NotificationCompat.Builder(context, Channels.TIMERS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(habit.name)
            .setContentText(text)
            .setContentIntent(openIntent(context, habit.id))
            .setColor(color)
            .setCategory(NotificationCompat.CATEGORY_STOPWATCH)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setStyle(style)
            // Asks for Live Update promotion (Android 16 QPR1+); ignored on older releases.
            .setRequestPromotedOngoing(true)
        if (s.timer.running) {
            // Before the goal the chip counts down to it; after, it counts the overtime up from it.
            builder.setWhen(now + s.remainingMs).setUsesChronometer(true).setChronometerCountDown(!reached).setShowWhen(true)
            builder.addAction(0, "Pause", actionIntent(context, ACTION_PAUSE, habit.id))
        } else {
            builder.setShowWhen(false).setShortCriticalText("Paused")
            builder.addAction(0, "Resume", actionIntent(context, ACTION_RESUME, habit.id))
        }
        builder.addAction(0, "Finish", actionIntent(context, ACTION_FINISH, habit.id))
        NotificationManagerCompat.from(context).notify(notificationId(habit.id), builder.build())
    }

    /** A one-off heads-up when a running timer reaches its goal; the Live Update itself stays silent. */
    fun alertGoalReached(context: Context, habit: Habit) {
        if (!canNotify(context)) return
        val n = NotificationCompat.Builder(context, Channels.TIMER_ALERTS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("${habit.name} · goal reached")
            .setContentText("${habit.goalLabel()} done. Finish the timer to log it.")
            .setColor(HabitColors.of(habit.colorIndex).toArgb())
            .setContentIntent(openIntent(context, habit.id))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setTimeoutAfter(2 * 60_000L)
            .addAction(0, "Finish", actionIntent(context, ACTION_FINISH, habit.id))
            .build()
        NotificationManagerCompat.from(context).notify(alertId(habit.id), n)
    }

    fun dismissAlert(context: Context, habitId: Long) = NotificationManagerCompat.from(context).cancel(alertId(habitId))

    private fun scheduleGoal(context: Context, habitId: Long, at: Long?) {
        // Reschedule only when the target moves by more than a second, not on every tick.
        val previous = scheduledGoals[habitId]
        if (at == null && previous == null) return
        if (at != null && previous != null && kotlin.math.abs(at - previous) < 1000) return
        val alarms = context.getSystemService(AlarmManager::class.java)
        val pi = actionIntent(context, ACTION_GOAL, habitId)
        if (at == null) {
            alarms.cancel(pi)
            scheduledGoals.remove(habitId)
        } else {
            if (alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            scheduledGoals[habitId] = at
        }
    }

    // Also covers the POST_NOTIFICATIONS grant on Android 13+, without failing on 12 where it doesn't exist.
    private fun canNotify(context: Context) = NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun openIntent(context: Context, habitId: Long): PendingIntent = PendingIntent.getActivity(
        context, notificationId(habitId),
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(MainActivity.EXTRA_HABIT_ID, habitId)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun actionIntent(context: Context, action: String, habitId: Long): PendingIntent = PendingIntent.getBroadcast(
        context, notificationId(habitId),
        Intent(context, TimerActionReceiver::class.java).setAction(action).putExtra(EXTRA_HABIT_ID, habitId),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun clock(ms: Long): String {
        val total = (ms.coerceAtLeast(0) + 999) / 1000
        return "%d:%02d".format(total / 60, total % 60)
    }
}

/** Pause / Resume / Finish buttons on the Live Update, and the goal-reached alarm. */
class TimerActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val container = context.container
        val repo = container.repository
        val habitId = intent.getLongExtra(TimerLiveUpdates.EXTRA_HABIT_ID, -1)
        val pending = goAsync()
        container.appScope.launch {
            try {
                val habit = repo.getHabit(habitId) ?: return@launch
                val timer = repo.getTimer(habitId) ?: return@launch
                when (intent.action) {
                    TimerLiveUpdates.ACTION_PAUSE -> repo.pauseTimer(habitId)
                    TimerLiveUpdates.ACTION_RESUME -> repo.startTimer(habit, timer.date)
                    TimerLiveUpdates.ACTION_FINISH -> {
                        repo.finishTimer(habit)
                        TimerLiveUpdates.dismissAlert(context, habitId)
                    }
                    TimerLiveUpdates.ACTION_GOAL -> if (timer.running) TimerLiveUpdates.alertGoalReached(context, habit)
                }
            } finally {
                pending.finish()
            }
        }
    }
}
