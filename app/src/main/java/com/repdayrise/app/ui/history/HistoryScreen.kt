package com.repdayrise.app.ui.history

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.repdayrise.app.AppContainer
import com.repdayrise.app.data.model.Habit
import com.repdayrise.app.domain.HabitLogic
import com.repdayrise.app.ui.components.HabitIcons
import com.repdayrise.app.ui.components.GlassTopBar
import com.repdayrise.app.ui.components.GlowCard
import com.repdayrise.app.ui.components.backdropSource
import com.repdayrise.app.ui.components.dayriseBackground
import com.repdayrise.app.ui.components.rememberBackdrop
import com.repdayrise.app.ui.components.IconBadge
import com.repdayrise.app.ui.components.SystemBars
import com.repdayrise.app.ui.components.MiniSunrise
import com.repdayrise.app.ui.components.MonthCalendar
import com.repdayrise.app.ui.components.SectionTitle
import com.repdayrise.app.ui.components.StatTile
import com.repdayrise.app.ui.theme.HabitColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

enum class LeaderboardMode(val label: String) { STREAK("Best streak"), TOTAL("Total days") }

data class HistoryDay(val date: LocalDate, val progress: Float, val done: Int, val total: Int)
data class LeaderEntry(val habit: Habit, val bestStreak: Int, val currentStreak: Int, val totalDays: Int, val unit: String)

data class HistoryUiState(
    val month: YearMonth = YearMonth.now(),
    val days: Map<LocalDate, HistoryDay> = emptyMap(),
    val selected: LocalDate? = null,
    val monthAverage: Float = 0f,
    val perfectDays: Int = 0,
    val topHabit: Pair<Habit, Int>? = null,
    val leaderboard: List<LeaderEntry> = emptyList(),
    val mode: LeaderboardMode = LeaderboardMode.STREAK,
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
    val today: LocalDate = LocalDate.now(),
)

class HistoryViewModel(container: AppContainer) : ViewModel() {
    private val repo = container.repository
    private val month = MutableStateFlow(YearMonth.now())
    private val selected = MutableStateFlow<LocalDate?>(null)
    private val mode = MutableStateFlow(LeaderboardMode.STREAK)

    val uiState: StateFlow<HistoryUiState> = combine(repo.habits, repo.entries, container.settings.settings, month, mode) { habits, entries, settings, month, mode ->
        val logic = HabitLogic(if (settings.weekStartsMonday) DayOfWeek.MONDAY else DayOfWeek.SUNDAY)
        val today = LocalDate.now()
        val active = habits.filter { !it.archived }
        val days = (1..month.lengthOfMonth()).map { month.atDay(it) }.associateWith { d ->
            val (done, total) = logic.dayCounts(active, entries, d)
            HistoryDay(d, logic.dayProgress(active, entries, d), done, total)
        }
        val elapsed = days.values.filter { !it.date.isAfter(today) && it.total > 0 }
        val avg = if (elapsed.isEmpty()) 0f else elapsed.map { it.progress }.average().toFloat()
        val perfect = elapsed.count { it.done == it.total }
        val top = active.map { it to logic.completedInRange(it, entries, month.atDay(1), month.atEndOfMonth()) }
            .filter { it.second > 0 }.maxByOrNull { it.second }
        val leaders = habits.map { h ->
            val s = logic.stats(h, entries, today)
            LeaderEntry(h, s.bestStreak, s.currentStreak, s.totalDays, s.streakUnit)
        }.sortedByDescending { if (mode == LeaderboardMode.STREAK) it.bestStreak else it.totalDays }.take(10)
        HistoryUiState(month, days, null, avg, perfect, top, leaders, mode, logic.weekDays(today).first().dayOfWeek, today)
    }.combine(selected) { s, sel -> s.copy(selected = sel) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    fun previousMonth() { month.value = month.value.minusMonths(1); selected.value = null }
    fun nextMonth() { if (month.value.isBefore(YearMonth.now())) { month.value = month.value.plusMonths(1); selected.value = null } }
    fun select(date: LocalDate) { selected.value = if (selected.value == date) null else date }
    fun setMode(m: LeaderboardMode) { mode.value = m }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: HistoryViewModel, onBack: () -> Unit, onOpenHabit: (Long) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val backdrop = rememberBackdrop()
    SystemBars()
    Scaffold(
        modifier = Modifier.dayriseBackground(),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            GlassTopBar(
                backdrop = backdrop,
                title = { Text("Sunrise history") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .backdropSource(backdrop)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = padding.calculateTopPadding() + 4.dp, bottom = 32.dp + padding.calculateBottomPadding()),
        ) {
            GlowCard {
                Column(Modifier.padding(14.dp)) {
                    MonthCalendar(
                        month = state.month,
                        weekStart = state.weekStart,
                        onPrevious = { viewModel.previousMonth() },
                        onNext = { viewModel.nextMonth() },
                        canGoNext = state.month.isBefore(YearMonth.now()),
                    ) { date ->
                        if (date == null) { Box(Modifier.size(42.dp)); return@MonthCalendar }
                        val day = state.days[date]
                        val future = date.isAfter(state.today)
                        val isSelected = state.selected == date
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(
                                Modifier
                                    .size(40.dp)
                                    .border(2.dp, if (isSelected) MaterialTheme.colorScheme.primary else if (date == state.today) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else Color.Transparent, CircleShape)
                                    .padding(3.dp)
                                    .clip(CircleShape)
                                    .clickable(enabled = !future) { viewModel.select(date) }
                                    .graphicsLayer { alpha = if (future) 0.35f else 1f },
                            ) {
                                MiniSunrise(progress = day?.progress ?: 0f, date = date, modifier = Modifier.fillMaxSize())
                            }
                            Text(
                                date.dayOfMonth.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (date == state.today) FontWeight.Bold else FontWeight.Medium,
                            )
                        }
                    }
                    val sel = state.selected?.let { state.days[it] }
                    if (sel != null) {
                        Spacer(Modifier.height(10.dp))
                        Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(44.dp).clip(CircleShape)) { MiniSunrise(sel.progress, sel.date, Modifier.fillMaxSize()) }
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(sel.date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")), style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        if (sel.total == 0) "No habits were scheduled" else "${sel.done} of ${sel.total} habits · ${(sel.progress * 100).roundToInt()}%",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("${(state.monthAverage * 100).roundToInt()}%", "Monthly progress", Modifier.weight(1f).fillMaxHeight())
                StatTile("${state.perfectDays}", "Perfect days", Modifier.weight(1f).fillMaxHeight())
            }
            state.topHabit?.let { (habit, count) ->
                Spacer(Modifier.height(16.dp))
                GlowCard(glow = HabitColors.of(habit.colorIndex), onClick = { onOpenHabit(habit.id) }) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(HabitIcons[habit.icon], HabitColors.of(habit.colorIndex), filled = true)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Top habit this month", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(habit.name, style = MaterialTheme.typography.titleMedium)
                        }
                        Text("$count ${if (count == 1) "day" else "days"}", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            SectionTitle("Leaderboard")
            Spacer(Modifier.height(10.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                LeaderboardMode.entries.forEachIndexed { i, m ->
                    SegmentedButton(selected = state.mode == m, onClick = { viewModel.setMode(m) }, shape = SegmentedButtonDefaults.itemShape(i, LeaderboardMode.entries.size), icon = {}) { Text(m.label) }
                }
            }
            Spacer(Modifier.height(10.dp))
            GlowCard {
                Column {
                    if (state.leaderboard.isEmpty()) {
                        Text("Your habits will rank here.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
                    }
                    state.leaderboard.forEachIndexed { i, e ->
                        Row(
                            Modifier.fillMaxWidth().clickable { onOpenHabit(e.habit.id) }.padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                                if (i < 3) Icon(Icons.Rounded.EmojiEvents, contentDescription = null, tint = listOf(Color(0xFFFFC14D), Color(0xFFB8BCC8), Color(0xFFCD8A55))[i], modifier = Modifier.size(20.dp))
                                else Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Spacer(Modifier.width(8.dp))
                            IconBadge(HabitIcons[e.habit.icon], HabitColors.of(e.habit.colorIndex), size = 36.dp, iconSize = 18.dp)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(e.habit.name, style = MaterialTheme.typography.titleSmall)
                                if (e.habit.archived) Text("Hall of Fame", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                            }
                            val value = if (state.mode == LeaderboardMode.STREAK) "${e.bestStreak} ${e.unit}" else "${e.totalDays} days"
                            Text(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}
