package com.repdayrise.app.ui.edit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.repdayrise.app.AppContainer
import com.repdayrise.app.data.model.DaysMask
import com.repdayrise.app.data.model.Habit
import com.repdayrise.app.data.model.HabitGroup
import com.repdayrise.app.data.model.HabitType
import com.repdayrise.app.data.model.HealthSource
import com.repdayrise.app.data.model.Reminder
import com.repdayrise.app.data.model.ScheduleType
import com.repdayrise.app.data.model.formatValue
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate

class EditHabitViewModel(
    private val container: AppContainer,
    private val habitId: Long?,
    initialGroupId: Long?,
) : ViewModel() {
    private val repo = container.repository
    val isNew: Boolean = habitId == null

    var name by mutableStateOf("")
    var icon by mutableStateOf("sun")
    var colorIndex by mutableStateOf(1)
    var type by mutableStateOf(HabitType.CHECK)
    var goalText by mutableStateOf("1")
    var unit by mutableStateOf("")
    var scheduleType by mutableStateOf(ScheduleType.DAILY)
    var daysMask by mutableStateOf(DaysMask.ALL)
    var timesPerPeriod by mutableStateOf(3)
    var groupId by mutableStateOf(initialGroupId)
    var startDate by mutableStateOf(LocalDate.now())
    var reminders by mutableStateOf<List<Reminder>>(emptyList())
    var healthSource by mutableStateOf(HealthSource.NONE)
    var note by mutableStateOf("")
    var loaded by mutableStateOf(habitId == null)
    var saved by mutableStateOf(false)
    var deleted by mutableStateOf(false)

    private var original: Habit? = null

    val groups: StateFlow<List<HabitGroup>> = repo.groups.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val healthAvailable: Boolean get() = container.health.isAvailable
    fun healthPermission(source: HealthSource): String? = container.health.permissionFor(source)

    init {
        if (habitId != null) {
            viewModelScope.launch {
                val h = repo.getHabit(habitId)
                if (h != null) {
                    original = h
                    name = h.name; icon = h.icon; colorIndex = h.colorIndex; type = h.type
                    goalText = formatValue(h.goal); unit = h.unit; scheduleType = h.scheduleType
                    daysMask = h.daysMask; timesPerPeriod = h.timesPerPeriod; groupId = h.groupId
                    startDate = h.startDate; reminders = h.reminders; healthSource = h.healthSource; note = h.note
                }
                loaded = true
            }
        }
    }

    val canSave: Boolean get() = name.isNotBlank() && (type == HabitType.CHECK || (goalText.toDoubleOrNull() ?: 0.0) > 0.0)

    fun changeType(t: HabitType) {
        type = t
        when (t) {
            HabitType.CHECK -> { healthSource = HealthSource.NONE }
            HabitType.COUNT -> if (goalText == "1" || goalText.isBlank()) goalText = "8"
            HabitType.TIMER -> { healthSource = HealthSource.NONE; if ((goalText.toDoubleOrNull() ?: 0.0) < 5) goalText = "30" }
        }
    }

    fun setHealth(source: HealthSource) {
        healthSource = source
        if (source != HealthSource.NONE) {
            type = HabitType.COUNT
            unit = source.unit
            goalText = formatValue(source.defaultGoal)
            if (name.isBlank()) name = source.label
            icon = when (source) {
                HealthSource.STEPS -> "walk"; HealthSource.DISTANCE -> "run"; HealthSource.WATER -> "water"
                HealthSource.SLEEP -> "sleep"; HealthSource.EXERCISE -> "gym"; HealthSource.CALORIES -> "flame"; HealthSource.NONE -> icon
            }
        }
    }

    fun toggleDay(day: DayOfWeek) {
        val next = DaysMask.toggle(daysMask, day)
        if (DaysMask.count(next) > 0) daysMask = next
    }

    fun addReminder(hour: Int, minute: Int) {
        reminders = reminders + Reminder(hour = hour, minute = minute, daysMask = if (scheduleType == ScheduleType.DAILY) daysMask else DaysMask.ALL)
    }

    fun updateReminder(index: Int, reminder: Reminder) {
        reminders = reminders.toMutableList().also { it[index] = reminder }
    }

    fun removeReminder(index: Int) {
        reminders = reminders.filterIndexed { i, _ -> i != index }
    }

    fun createGroup(name: String) {
        viewModelScope.launch { groupId = repo.createGroup(name.trim()) }
    }

    fun deleteGroup(id: Long) {
        viewModelScope.launch { repo.deleteGroup(id); if (groupId == id) groupId = null }
    }

    fun save() {
        if (!canSave) return
        val base = original ?: Habit(name = name)
        val habit = base.copy(
            name = name.trim(),
            icon = icon,
            colorIndex = colorIndex,
            type = type,
            goal = if (type == HabitType.CHECK) 1.0 else (goalText.toDoubleOrNull() ?: 1.0),
            unit = if (type == HabitType.COUNT) unit.trim() else if (type == HabitType.TIMER) "min" else "",
            scheduleType = scheduleType,
            daysMask = daysMask,
            timesPerPeriod = timesPerPeriod,
            groupId = groupId,
            startDate = startDate,
            reminders = reminders,
            healthSource = if (type == HabitType.COUNT) healthSource else HealthSource.NONE,
            note = note.trim(),
        )
        viewModelScope.launch {
            repo.saveHabit(habit)
            saved = true
        }
    }

    fun delete() {
        val id = habitId ?: return
        viewModelScope.launch { repo.deleteHabit(id); deleted = true }
    }
}
