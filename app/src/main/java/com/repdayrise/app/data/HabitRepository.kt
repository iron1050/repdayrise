package com.repdayrise.app.data

import com.repdayrise.app.data.db.DayriseDatabase
import com.repdayrise.app.data.db.EntryEntity
import com.repdayrise.app.data.db.GroupEntity
import com.repdayrise.app.data.db.HabitEntity
import com.repdayrise.app.data.db.ReminderEntity
import com.repdayrise.app.data.db.TimerEntity
import com.repdayrise.app.data.model.ActiveTimer
import com.repdayrise.app.data.model.Habit
import com.repdayrise.app.data.model.HabitGroup
import com.repdayrise.app.data.model.HabitType
import com.repdayrise.app.data.model.Reminder
import com.repdayrise.app.domain.EntryMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Hooks the repository calls after data changes (widgets, reminders). */
interface DataChangeListener {
    fun onHabitsChanged()
    fun onEntriesChanged()
}

class HabitRepository(
    private val db: DayriseDatabase,
    private val scope: CoroutineScope,
) {
    var listener: DataChangeListener? = null

    private val habitDao = db.habitDao()
    private val entryDao = db.entryDao()
    private val groupDao = db.groupDao()
    private val reminderDao = db.reminderDao()
    private val timerDao = db.timerDao()

    val habits: Flow<List<Habit>> = combine(habitDao.observeAll(), reminderDao.observeAll()) { habits, reminders ->
        val byHabit = reminders.groupBy { it.habitId }
        habits.map { it.toModel(byHabit[it.id].orEmpty().map(ReminderEntity::toModel)) }
    }.stateIn(scope, SharingStarted.Eagerly, emptyList())

    val activeHabits: Flow<List<Habit>> = habits.map { list -> list.filter { !it.archived } }
    val archivedHabits: Flow<List<Habit>> = habits.map { list -> list.filter { it.archived } }

    val entries: Flow<EntryMap> = entryDao.observeAll().map { list ->
        val map = HashMap<Long, HashMap<Long, Double>>()
        for (e in list) map.getOrPut(e.habitId) { HashMap() }[e.date] = e.value
        map
    }.stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val groups: Flow<List<HabitGroup>> = groupDao.observeAll().map { list -> list.map(GroupEntity::toModel) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val timers: Flow<List<ActiveTimer>> = timerDao.observeAll().map { list -> list.map(TimerEntity::toModel) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    suspend fun getHabit(id: Long): Habit? {
        val entity = habitDao.getById(id) ?: return null
        val reminders = reminderDao.getAll().filter { it.habitId == id }.map(ReminderEntity::toModel)
        return entity.toModel(reminders)
    }

    suspend fun getAllHabitsOnce(): List<Habit> {
        val reminders = reminderDao.getAll().groupBy { it.habitId }
        return habitDao.getAll().map { it.toModel(reminders[it.id].orEmpty().map(ReminderEntity::toModel)) }
    }

    suspend fun getEntriesOnce(): EntryMap {
        val map = HashMap<Long, HashMap<Long, Double>>()
        for (e in entryDao.getAll()) map.getOrPut(e.habitId) { HashMap() }[e.date] = e.value
        return map
    }

    suspend fun getReminder(id: Long): Reminder? = reminderDao.getById(id)?.toModel()

    suspend fun saveHabit(habit: Habit): Long {
        val entity = habit.toEntity()
        val id = if (habit.id == 0L) {
            habitDao.insert(entity.copy(sortOrder = habitDao.nextSortOrder()))
        } else {
            habitDao.update(entity); habit.id
        }
        reminderDao.replaceForHabit(id, habit.reminders.map { it.toEntity() })
        listener?.onHabitsChanged()
        return id
    }

    suspend fun setArchived(id: Long, archived: Boolean) {
        habitDao.setArchived(id, archived, if (archived) System.currentTimeMillis() else null)
        listener?.onHabitsChanged()
    }

    suspend fun deleteHabit(id: Long) {
        habitDao.delete(id)
        listener?.onHabitsChanged()
    }

    suspend fun reorder(ids: List<Long>) {
        ids.forEachIndexed { index, id -> habitDao.updateSortOrder(id, index) }
    }

    suspend fun setValue(habitId: Long, date: LocalDate, value: Double) {
        val epoch = date.toEpochDay()
        if (value <= 0.0) entryDao.delete(habitId, epoch)
        else entryDao.upsert(EntryEntity(habitId, epoch, value, System.currentTimeMillis()))
        listener?.onEntriesChanged()
    }

    suspend fun toggle(habit: Habit, date: LocalDate) {
        val current = entryDao.get(habit.id, date.toEpochDay())?.value ?: 0.0
        val done = current + 1e-9 >= habit.effectiveGoal
        setValue(habit.id, date, if (done) 0.0 else habit.effectiveGoal)
    }

    suspend fun increment(habit: Habit, date: LocalDate, delta: Double) {
        val current = entryDao.get(habit.id, date.toEpochDay())?.value ?: 0.0
        setValue(habit.id, date, (current + delta).coerceAtLeast(0.0))
    }

    suspend fun currentValue(habitId: Long, date: LocalDate): Double =
        entryDao.get(habitId, date.toEpochDay())?.value ?: 0.0

    // Groups
    suspend fun createGroup(name: String): Long {
        val next = (groupDao.getAll().maxOfOrNull { it.sortOrder } ?: -1) + 1
        return groupDao.insert(GroupEntity(name = name, sortOrder = next, collapsed = false))
    }

    suspend fun renameGroup(id: Long, name: String) {
        val g = groupDao.getAll().firstOrNull { it.id == id } ?: return
        groupDao.update(g.copy(name = name))
    }

    suspend fun setGroupCollapsed(id: Long, collapsed: Boolean) = groupDao.setCollapsed(id, collapsed)

    suspend fun deleteGroup(id: Long) {
        groupDao.detachHabits(id)
        groupDao.delete(id)
    }

    // Timers
    suspend fun startTimer(habit: Habit, date: LocalDate) {
        val existing = timerDao.get(habit.id)
        val now = System.currentTimeMillis()
        val timer = if (existing != null && existing.date == date.toEpochDay()) {
            existing.copy(startedAt = now, running = true)
        } else {
            TimerEntity(habit.id, date.toEpochDay(), now, 0L, true)
        }
        timerDao.upsert(timer)
    }

    suspend fun pauseTimer(habitId: Long) {
        val t = timerDao.get(habitId) ?: return
        if (!t.running) return
        val now = System.currentTimeMillis()
        timerDao.upsert(t.copy(accumulatedMs = t.accumulatedMs + (now - t.startedAt).coerceAtLeast(0), running = false))
    }

    /** Stops the timer and commits elapsed minutes to the day's entry. */
    suspend fun finishTimer(habit: Habit) {
        val t = timerDao.get(habit.id) ?: return
        val elapsed = t.toModel().elapsedMs()
        val minutes = elapsed / 60000.0
        timerDao.delete(habit.id)
        if (minutes > 0.05) increment(habit, LocalDate.ofEpochDay(t.date), minutes)
        else listener?.onEntriesChanged()
    }

    suspend fun cancelTimer(habitId: Long) {
        timerDao.delete(habitId)
    }

    suspend fun getTimer(habitId: Long): ActiveTimer? = timerDao.get(habitId)?.toModel()

    fun launch(block: suspend () -> Unit) {
        scope.launch { block() }
    }
}

// Mapping helpers
fun HabitEntity.toModel(reminders: List<Reminder>): Habit = Habit(
    id = id, name = name, icon = icon, colorIndex = colorIndex, type = type, goal = goal, unit = unit,
    scheduleType = scheduleType, daysMask = daysMask, timesPerPeriod = timesPerPeriod, groupId = groupId,
    startDate = LocalDate.ofEpochDay(startDate), createdAt = createdAt, archived = archived, archivedAt = archivedAt,
    sortOrder = sortOrder, healthSource = healthSource, note = note, reminders = reminders,
)

fun Habit.toEntity(): HabitEntity = HabitEntity(
    id = id, name = name.trim(), icon = icon, colorIndex = colorIndex, type = type,
    goal = if (type == HabitType.CHECK) 1.0 else goal, unit = unit.trim(),
    scheduleType = scheduleType, daysMask = daysMask, timesPerPeriod = timesPerPeriod.coerceAtLeast(1), groupId = groupId,
    startDate = startDate.toEpochDay(), createdAt = createdAt, archived = archived, archivedAt = archivedAt,
    sortOrder = sortOrder, healthSource = healthSource, note = note,
)

fun ReminderEntity.toModel() = Reminder(id, habitId, hour, minute, daysMask, enabled)
fun Reminder.toEntity() = ReminderEntity(id, habitId, hour, minute, daysMask, enabled)
fun GroupEntity.toModel() = HabitGroup(id, name, sortOrder, collapsed)
fun TimerEntity.toModel() = ActiveTimer(habitId, LocalDate.ofEpochDay(date), startedAt, accumulatedMs, running)
