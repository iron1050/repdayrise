package com.repdayrise.app.ui.habit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.repdayrise.app.AppContainer
import com.repdayrise.app.data.model.Habit
import com.repdayrise.app.data.model.HabitType
import com.repdayrise.app.data.model.ScheduleType
import com.repdayrise.app.data.model.formatDuration
import com.repdayrise.app.data.model.formatValue
import com.repdayrise.app.domain.DayStatus
import com.repdayrise.app.domain.EntryMap
import com.repdayrise.app.domain.HabitLogic
import com.repdayrise.app.domain.HabitStats
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

enum class StatPeriod(val label: String) { WEEK("Week"), MONTH("Month"), YEAR("Year"), ALL("All") }

data class ChartData(
    val values: List<Float> = emptyList(),
    val labels: List<String> = emptyList(),
    val highlight: Int = -1,
    val total: String = "0",
    val average: String = "0",
    val comparison: String = "",
    val comparisonPositive: Boolean = true,
    val unit: String = "",
)

data class DetailUiState(
    val habit: Habit? = null,
    val stats: HabitStats = HabitStats(0, 0, 0, "days"),
    val month: YearMonth = YearMonth.now(),
    val monthStatuses: Map<LocalDate, DayStatus> = emptyMap(),
    val monthCompletion: Float = 0f,
    val monthDone: Int = 0,
    val period: StatPeriod = StatPeriod.WEEK,
    val chart: ChartData = ChartData(),
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
    val today: LocalDate = LocalDate.now(),
    val selection: Set<LocalDate> = emptySet(),
    val loaded: Boolean = false,
    val deleted: Boolean = false,
)

class HabitDetailViewModel(private val container: AppContainer, private val habitId: Long) : ViewModel() {
    private val repo = container.repository
    private val month = MutableStateFlow(YearMonth.now())
    private val period = MutableStateFlow(StatPeriod.WEEK)
    private val selection = MutableStateFlow<Set<LocalDate>>(emptySet())
    private val deleted = MutableStateFlow(false)

    val uiState: StateFlow<DetailUiState> = combine(
        repo.habits, repo.entries, container.settings.settings, month, period,
    ) { habits, entries, settings, month, period ->
        val habit = habits.firstOrNull { it.id == habitId }
        val logic = HabitLogic(if (settings.weekStartsMonday) DayOfWeek.MONDAY else DayOfWeek.SUNDAY)
        if (habit == null) DetailUiState(loaded = true, weekStart = logic.weekStartOf(LocalDate.now()).dayOfWeek)
        else build(habit, entries, logic, month, period)
    }.combine(selection) { s, sel -> s.copy(selection = sel) }
        .combine(deleted) { s, d -> s.copy(deleted = d) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DetailUiState())

    private fun build(habit: Habit, entries: EntryMap, logic: HabitLogic, month: YearMonth, period: StatPeriod): DetailUiState {
        val today = LocalDate.now()
        val statuses = (1..month.lengthOfMonth()).associate { d ->
            val date = month.atDay(d)
            date to logic.dayStatus(habit, entries, date)
        }
        return DetailUiState(
            habit = habit,
            stats = logic.stats(habit, entries, today),
            month = month,
            monthStatuses = statuses,
            monthCompletion = logic.monthCompletion(habit, entries, month, today),
            monthDone = statuses.values.count { it.completed },
            period = period,
            chart = chart(habit, entries, logic, period, today),
            weekStart = logic.weekDays(today).first().dayOfWeek,
            today = today,
            loaded = true,
        )
    }

    private fun chart(habit: Habit, entries: EntryMap, logic: HabitLogic, period: StatPeriod, today: LocalDate): ChartData {
        val measurable = habit.isMeasurable
        fun fmt(v: Double): String = if (habit.type == HabitType.TIMER) formatDuration(v) else formatValue(v)
        val unit = when (habit.type) { HabitType.CHECK -> "days"; HabitType.COUNT -> habit.unit; HabitType.TIMER -> "" }
        return when (period) {
            StatPeriod.WEEK -> {
                val days = logic.weekDays(today)
                val values = days.map { if (measurable) logic.value(entries, habit.id, it).toFloat() else if (logic.isCompleted(habit, entries, it)) 1f else 0f }
                val labels = days.map { it.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()) }
                val total = values.sumOf { it.toDouble() }
                val prevDays = logic.weekDays(today.minusWeeks(1))
                val prev = prevDays.sumOf { if (measurable) logic.value(entries, habit.id, it) else if (logic.isCompleted(habit, entries, it)) 1.0 else 0.0 }
                val elapsed = days.count { !it.isAfter(today) }.coerceAtLeast(1)
                ChartData(values, labels, days.indexOf(today), if (measurable) fmt(total) else "${total.toInt()}", fmt(total / elapsed), compare(total, prev, "last week"), total >= prev, unit)
            }
            StatPeriod.MONTH -> {
                val m = YearMonth.from(today)
                val days = (1..m.lengthOfMonth()).map { m.atDay(it) }
                val values = days.map { if (measurable) logic.value(entries, habit.id, it).toFloat() else if (logic.isCompleted(habit, entries, it)) 1f else 0f }
                val labels = days.map { if (it.dayOfMonth == 1 || it.dayOfMonth % 5 == 0) it.dayOfMonth.toString() else "" }
                val total = values.sumOf { it.toDouble() }
                val pm = m.minusMonths(1)
                val prev = if (measurable) logic.sumInRange(habit, entries, pm.atDay(1), pm.atEndOfMonth()) else logic.completedInRange(habit, entries, pm.atDay(1), pm.atEndOfMonth()).toDouble()
                ChartData(values, days.map { it.dayOfMonth.toString() }, today.dayOfMonth - 1, if (measurable) fmt(total) else "${total.toInt()}", fmt(total / today.dayOfMonth), compare(total, prev, "last month"), total >= prev, unit)
            }
            StatPeriod.YEAR -> {
                val months = (1..12).map { YearMonth.of(today.year, it) }
                val values = months.map { logic.completedInRange(habit, entries, it.atDay(1), it.atEndOfMonth()).toFloat() }
                val labels = months.map { it.month.getDisplayName(TextStyle.NARROW, Locale.getDefault()) }
                val total = values.sumOf { it.toDouble() }
                val prev = logic.completedInRange(habit, entries, LocalDate.of(today.year - 1, 1, 1), LocalDate.of(today.year - 1, 12, 31)).toDouble()
                ChartData(values, labels, today.monthValue - 1, "${total.toInt()}", "${(total / today.monthValue).roundToInt()}/mo", compare(total, prev, "last year"), total >= prev, "days")
            }
            StatPeriod.ALL -> {
                var m = YearMonth.from(habit.startDate)
                val end = YearMonth.from(today)
                val months = mutableListOf<YearMonth>()
                while (!m.isAfter(end) && months.size < 36) { months.add(m); m = m.plusMonths(1) }
                if (months.size == 36) { months.clear(); var k = end.minusMonths(35); while (!k.isAfter(end)) { months.add(k); k = k.plusMonths(1) } }
                val values = months.map { logic.completedInRange(habit, entries, it.atDay(1), it.atEndOfMonth()).toFloat() }
                val labels = months.map { it.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(1) }
                val total = values.sumOf { it.toDouble() }
                ChartData(values, labels, months.lastIndex, "${total.toInt()}", "${(total / months.size.coerceAtLeast(1)).roundToInt()}/mo", "since ${habit.startDate.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} ${habit.startDate.year}", true, "days")
            }
        }
    }

    private fun compare(current: Double, previous: Double, label: String): String {
        val diff = current - previous
        val sign = if (diff >= 0) "+" else "−"
        val d = kotlin.math.abs(diff)
        val txt = if (d == Math.floor(d)) d.toInt().toString() else String.format(Locale.US, "%.1f", d)
        return "$sign$txt vs $label"
    }

    fun previousMonth() { month.value = month.value.minusMonths(1) }
    fun nextMonth() { if (month.value.isBefore(YearMonth.now())) month.value = month.value.plusMonths(1) }
    fun setPeriod(p: StatPeriod) { period.value = p }

    fun toggleDay(date: LocalDate) {
        val habit = uiState.value.habit ?: return
        if (date.isAfter(LocalDate.now())) return
        viewModelScope.launch { repo.toggle(habit, date) }
    }

    fun setValue(date: LocalDate, value: Double) {
        val habit = uiState.value.habit ?: return
        viewModelScope.launch { repo.setValue(habit.id, date, value) }
    }

    fun toggleSelection(date: LocalDate) {
        if (date.isAfter(LocalDate.now())) return
        selection.value = if (date in selection.value) selection.value - date else selection.value + date
    }

    fun clearSelection() { selection.value = emptySet() }

    fun applySelection(value: Double?) {
        val habit = uiState.value.habit ?: return
        val dates = selection.value
        viewModelScope.launch {
            dates.forEach { repo.setValue(habit.id, it, value ?: habit.effectiveGoal) }
            selection.value = emptySet()
        }
    }

    fun setArchived(archived: Boolean) {
        viewModelScope.launch { repo.setArchived(habitId, archived) }
    }

    fun delete() {
        viewModelScope.launch {
            repo.deleteHabit(habitId)
            deleted.value = true
        }
    }
}
