package com.repdayrise.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.repdayrise.app.AppContainer
import com.repdayrise.app.data.Settings
import com.repdayrise.app.data.model.ActiveTimer
import com.repdayrise.app.data.model.Habit
import com.repdayrise.app.data.model.HabitGroup
import com.repdayrise.app.data.model.HabitType
import com.repdayrise.app.data.model.ScheduleType
import com.repdayrise.app.domain.EntryMap
import com.repdayrise.app.domain.HabitLogic
import com.repdayrise.app.notifications.TimerNotifier
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate

data class HabitRowUi(
    val habit: Habit,
    val value: Double,
    val fraction: Float,
    val completed: Boolean,
    val streak: Int,
    val streakUnit: String,
    val timer: ActiveTimer?,
    val periodDone: Int,
    val periodGoal: Int,
)

data class HomeSection(val group: HabitGroup?, val rows: List<HabitRowUi>, val collapsed: Boolean)

data class DayUi(val date: LocalDate, val progress: Float, val isToday: Boolean, val isFuture: Boolean, val done: Int, val total: Int)

data class HomeUiState(
    val today: LocalDate = LocalDate.now(),
    val selectedDate: LocalDate = LocalDate.now(),
    val sections: List<HomeSection> = emptyList(),
    val progress: Float = 0f,
    val done: Int = 0,
    val total: Int = 0,
    val week: List<DayUi> = emptyList(),
    val hasAnyHabits: Boolean = false,
    val haptics: Boolean = true,
    val loaded: Boolean = false,
) {
    val isFuture: Boolean get() = selectedDate.isAfter(today)
}

private data class Snapshot(
    val habits: List<Habit>,
    val entries: EntryMap,
    val groups: List<HabitGroup>,
    val timers: List<ActiveTimer>,
    val settings: Settings,
)

class HomeViewModel(private val container: AppContainer) : ViewModel() {
    private val repo = container.repository
    private val selectedDate = MutableStateFlow(LocalDate.now())
    private val tick = MutableStateFlow(0L)

    val uiState: StateFlow<HomeUiState> = combine(
        repo.activeHabits, repo.entries, repo.groups, repo.timers, container.settings.settings,
    ) { habits, entries, groups, timers, settings -> Snapshot(habits, entries, groups, timers, settings) }
        .combine(selectedDate) { snap, date -> build(snap, date) }
        .combine(tick) { state, _ -> state.copy(today = LocalDate.now()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    private fun build(s: Snapshot, date: LocalDate): HomeUiState {
        val logic = HabitLogic(if (s.settings.weekStartsMonday) DayOfWeek.MONDAY else DayOfWeek.SUNDAY)
        val today = LocalDate.now()
        val due = s.habits.filter { logic.isDue(it, s.entries, date) }
        val rows = due.map { habit ->
            val v = logic.value(s.entries, habit.id, date)
            val stats = logic.stats(habit, s.entries, today)
            val (pStart, pEnd) = logic.periodRange(habit, date)
            HabitRowUi(
                habit = habit,
                value = v,
                fraction = logic.fraction(habit, v),
                completed = logic.isCompleted(habit, v),
                streak = stats.currentStreak,
                streakUnit = stats.streakUnit,
                timer = s.timers.firstOrNull { it.habitId == habit.id && it.date == date },
                periodDone = if (habit.scheduleType == ScheduleType.DAILY) 0 else logic.completedInRange(habit, s.entries, pStart, pEnd),
                periodGoal = habit.timesPerPeriod,
            )
        }
        val sorted = if (s.settings.completedToBottom) rows.sortedBy { it.completed } else rows
        val ungrouped = sorted.filter { it.habit.groupId == null || s.groups.none { g -> g.id == it.habit.groupId } }
        val sections = buildList {
            if (ungrouped.isNotEmpty()) add(HomeSection(null, ungrouped, false))
            for (g in s.groups) {
                val inGroup = sorted.filter { it.habit.groupId == g.id }
                if (inGroup.isNotEmpty()) add(HomeSection(g, inGroup, g.collapsed))
            }
        }
        val (done, total) = logic.dayCounts(s.habits, s.entries, date)
        val week = logic.weekDays(date).map { d ->
            val (dd, tt) = logic.dayCounts(s.habits, s.entries, d)
            DayUi(d, logic.dayProgress(s.habits, s.entries, d), d == today, d.isAfter(today), dd, tt)
        }
        return HomeUiState(
            today = today,
            selectedDate = date,
            sections = sections,
            progress = logic.dayProgress(s.habits, s.entries, date),
            done = done,
            total = total,
            week = week,
            hasAnyHabits = s.habits.isNotEmpty(),
            haptics = s.settings.haptics,
            loaded = true,
        )
    }

    fun selectDate(date: LocalDate) { selectedDate.value = date }
    fun shiftWeek(delta: Long) { selectedDate.value = selectedDate.value.plusWeeks(delta) }
    fun goToday() { selectedDate.value = LocalDate.now() }
    fun refreshToday() { tick.value = System.currentTimeMillis() }

    fun toggle(habit: Habit) {
        val date = selectedDate.value
        viewModelScope.launch { repo.toggle(habit, date) }
    }

    fun increment(habit: Habit, delta: Double = 1.0) {
        val date = selectedDate.value
        viewModelScope.launch { repo.increment(habit, date, delta) }
    }

    fun setValue(habit: Habit, value: Double) {
        val date = selectedDate.value
        viewModelScope.launch { repo.setValue(habit.id, date, value) }
    }

    fun toggleTimer(habit: Habit, timer: ActiveTimer?) {
        val date = selectedDate.value
        viewModelScope.launch {
            if (timer == null || !timer.running) {
                repo.startTimer(habit, date)
                val t = repo.getTimer(habit.id)
                if (t != null) TimerNotifier.show(container.context, habit, t.elapsedMs(), true)
            } else {
                repo.pauseTimer(habit.id)
                val t = repo.getTimer(habit.id)
                if (t != null) TimerNotifier.show(container.context, habit, t.elapsedMs(), false)
            }
        }
    }

    fun finishTimer(habit: Habit) {
        viewModelScope.launch {
            repo.finishTimer(habit)
            TimerNotifier.dismiss(container.context)
        }
    }

    fun cancelTimer(habit: Habit) {
        viewModelScope.launch {
            repo.cancelTimer(habit.id)
            TimerNotifier.dismiss(container.context)
        }
    }

    fun setGroupCollapsed(group: HabitGroup, collapsed: Boolean) {
        viewModelScope.launch { repo.setGroupCollapsed(group.id, collapsed) }
    }

    fun syncHealth() {
        viewModelScope.launch { runCatching { container.healthSync.sync() } }
    }
}
