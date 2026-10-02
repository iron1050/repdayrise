package com.repdayrise.app.ui.partners

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.repdayrise.app.AppContainer
import com.repdayrise.app.data.model.Habit
import com.repdayrise.app.data.model.HabitType
import com.repdayrise.app.data.model.ScheduleType
import com.repdayrise.app.data.model.formatDuration
import com.repdayrise.app.data.model.formatValue
import com.repdayrise.app.data.sharing.Following
import com.repdayrise.app.data.sharing.SharingRepository
import com.repdayrise.app.data.sharing.SharingState
import com.repdayrise.app.domain.HabitLogic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** "just now", "12 min ago", "yesterday", "Mar 4". */
fun relativeTime(millis: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = (now - millis) / 60_000
    return when {
        millis <= 0 -> "never"
        minutes < 1 -> "just now"
        minutes < 60 -> "$minutes min ago"
        minutes < 24 * 60 -> "${minutes / 60} h ago"
        minutes < 48 * 60 -> "yesterday"
        else -> Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("MMM d"))
    }
}

private fun Following.logic() = HabitLogic(if (snapshot?.weekStartsMonday != false) DayOfWeek.MONDAY else DayOfWeek.SUNDAY)

data class PartnerCard(
    val shareId: String,
    val name: String,
    val progress: Float,
    val done: Int,
    val total: Int,
    val updatedAt: Long,
    val ended: Boolean,
    val hasData: Boolean,
)

data class PartnersUiState(
    val sharing: SharingState = SharingState(),
    val serverUrl: String = "",
    val habits: List<Habit> = emptyList(),
    val cards: List<PartnerCard> = emptyList(),
    val today: LocalDate = LocalDate.now(),
    val busy: Boolean = false,
    val message: String? = null,
)

class PartnersViewModel(container: AppContainer) : ViewModel() {
    private val sharing = container.sharing
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<PartnersUiState> = combine(sharing.state, container.repository.activeHabits, busy, message) { state, habits, busy, message ->
        val today = LocalDate.now()
        val cards = state.following.map { f ->
            val list = f.snapshot?.habits.orEmpty().map { it.toHabit() }
            val entries = f.snapshot?.entries.orEmpty()
            val logic = f.logic()
            val (done, total) = logic.dayCounts(list, entries, today)
            PartnerCard(f.shareId, f.ownerName, logic.dayProgress(list, entries, today), done, total, f.updatedAt, f.ended, f.snapshot != null)
        }
        PartnersUiState(state, SharingRepository.normalizeUrl(state.serverUrl).ifBlank { sharing.defaultServerUrl }, habits, cards, today, busy, message)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PartnersUiState(sharing = sharing.state.value))

    init { refresh(quiet = true) }

    private fun run(block: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            busy.value = true
            block().onFailure { message.value = it.message }
            busy.value = false
        }
    }

    /** [quiet] skips the error message: used on open, where being offline isn't worth interrupting for. */
    fun refresh(quiet: Boolean = false) {
        viewModelScope.launch {
            busy.value = true
            val following = sharing.refreshFollowing()
            val outgoing = sharing.refreshOutgoing()
            sharing.push()
            if (!quiet) (following.exceptionOrNull() ?: outgoing.exceptionOrNull())?.let { message.value = it.message }
            busy.value = false
        }
    }

    fun messageShown() { message.value = null }
    fun setServerUrl(url: String) { viewModelScope.launch { sharing.setServerUrl(url) } }
    fun startSharing(name: String) = run { sharing.startSharing(name) }
    fun stopSharing() = run { sharing.stopSharing() }
    fun rotateCode() = run { sharing.rotateCode() }
    fun removePartner(id: String) = run { sharing.removePartner(id) }
    fun setHabitShared(id: Long, shared: Boolean) { viewModelScope.launch { sharing.setHabitShared(id, shared) } }
    fun follow(code: String, name: String) = run { sharing.follow(SharingRepository.extractCode(code), name) }
    fun unfollow(shareId: String) { viewModelScope.launch { sharing.unfollow(shareId) } }
}

data class PartnerHabitRow(
    val habit: Habit,
    val fraction: Float,
    val completed: Boolean,
    val due: Boolean,
    val streak: Int,
    val subtitle: String,
    val heatmap: Map<Long, Float>,
    val values: Map<Long, Double>,
    val yearDone: Int,
)

data class PartnerDetailUiState(
    val loaded: Boolean = false,
    val found: Boolean = false,
    val name: String = "",
    val ended: Boolean = false,
    val hasData: Boolean = false,
    val updatedAt: Long = 0,
    val today: LocalDate = LocalDate.now(),
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
    val progress: Float = 0f,
    val done: Int = 0,
    val total: Int = 0,
    val weekAverage: Float = 0f,
    val perfectDays: Int = 0,
    val heatmap: Map<Long, Float> = emptyMap(),
    val rows: List<PartnerHabitRow> = emptyList(),
    val busy: Boolean = false,
    val message: String? = null,
)

class PartnerDetailViewModel(container: AppContainer, private val shareId: String) : ViewModel() {
    private val sharing = container.sharing
    private val busy = MutableStateFlow(false)
    private val message = MutableStateFlow<String?>(null)

    val uiState: StateFlow<PartnerDetailUiState> = combine(sharing.state, busy, message) { state, busy, message ->
        val f = state.following.firstOrNull { it.shareId == shareId }
            ?: return@combine PartnerDetailUiState(loaded = true)
        val today = LocalDate.now()
        val logic = f.logic()
        val habits = f.snapshot?.habits.orEmpty().map { it.toHabit() }
        val entries = f.snapshot?.entries.orEmpty()
        val from = logic.graphStart(today)
        val (done, total) = logic.dayCounts(habits, entries, today)
        val heatmap = logic.overallHeatmap(habits, entries, from, today)
        val week = logic.weekDays(today).filter { !it.isAfter(today) }
        val rows = habits.map { h ->
            val value = logic.value(entries, h.id, today)
            val heat = logic.habitHeatmap(h, entries, from, today)
            PartnerHabitRow(
                habit = h,
                fraction = logic.fraction(h, value),
                completed = logic.isCompleted(h, value),
                due = logic.isDue(h, entries, today),
                streak = logic.stats(h, entries, today).currentStreak,
                subtitle = when {
                    h.type == HabitType.COUNT -> "${formatValue(value)} / ${formatValue(h.goal)} ${h.unit}".trim()
                    h.type == HabitType.TIMER -> "${formatDuration(value)} / ${formatDuration(h.goal)}"
                    h.scheduleType == ScheduleType.DAILY -> h.scheduleLabel()
                    else -> "${logic.completedCountInPeriod(h, entries, today)}/${h.timesPerPeriod} this ${if (h.scheduleType == ScheduleType.WEEKLY) "week" else "month"}"
                },
                heatmap = heat,
                values = entries[h.id].orEmpty(),
                yearDone = heat.values.count { it >= 0.999f },
            )
        }.sortedByDescending { it.due }
        PartnerDetailUiState(
            loaded = true, found = true, name = f.ownerName, ended = f.ended, hasData = f.snapshot != null, updatedAt = f.updatedAt,
            today = today, weekStart = logic.weekStartOf(today).dayOfWeek,
            progress = logic.dayProgress(habits, entries, today), done = done, total = total,
            weekAverage = week.map { logic.dayProgress(habits, entries, it) }.average().toFloat(),
            perfectDays = heatmap.values.count { it >= 0.999f },
            heatmap = heatmap, rows = rows, busy = busy, message = message,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PartnerDetailUiState())

    init { refresh(quiet = true) }

    fun refresh(quiet: Boolean = false) {
        viewModelScope.launch {
            busy.value = true
            val result = sharing.refreshFollowing()
            if (!quiet) result.exceptionOrNull()?.let { message.value = it.message }
            busy.value = false
        }
    }

    fun messageShown() { message.value = null }
    fun unfollow() { viewModelScope.launch { sharing.unfollow(shareId) } }
}
