package com.repdayrise.app.ui.history

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.NorthEast
import androidx.compose.material.icons.rounded.SouthEast
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.repdayrise.app.AppContainer
import com.repdayrise.app.data.model.Habit
import com.repdayrise.app.data.model.countLabel
import com.repdayrise.app.domain.HabitLogic
import com.repdayrise.app.ui.components.ContributionGraph
import com.repdayrise.app.ui.components.DashDay
import com.repdayrise.app.ui.components.DashTile
import com.repdayrise.app.ui.components.DayTileStrip
import com.repdayrise.app.ui.components.FlameBarChart
import com.repdayrise.app.ui.components.GlowCard
import com.repdayrise.app.ui.components.HabitIcons
import com.repdayrise.app.ui.components.IconBadge
import com.repdayrise.app.ui.components.MiniSunrise
import com.repdayrise.app.ui.components.MonthCalendar
import com.repdayrise.app.ui.components.MonthLineChart
import com.repdayrise.app.ui.components.ProgressPill
import com.repdayrise.app.ui.components.SectionTitle
import com.repdayrise.app.ui.components.SkyScene
import com.repdayrise.app.ui.components.SystemBars
import com.repdayrise.app.ui.components.TickGauge
import com.repdayrise.app.ui.components.backdropSource
import com.repdayrise.app.ui.components.completionLevel
import com.repdayrise.app.ui.components.dayriseBackground
import com.repdayrise.app.ui.components.frosted
import com.repdayrise.app.ui.components.glassBorder
import com.repdayrise.app.ui.components.glassTint
import com.repdayrise.app.ui.components.levelColor
import com.repdayrise.app.ui.components.rememberBackdrop
import com.repdayrise.app.ui.components.rememberSkyState
import com.repdayrise.app.ui.theme.HabitColors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.Month
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

enum class LeaderboardMode(val label: String) { STREAK("Best streak"), TOTAL("Total days") }
enum class DashSheet { MONTH, TOP }

data class HistoryDay(val date: LocalDate, val progress: Float, val done: Int, val total: Int)
data class LeaderEntry(val habit: Habit, val bestStreak: Int, val bestStreakDays: Int, val currentStreak: Int, val totalDays: Int, val unit: String)
data class HabitMonthRow(val habit: Habit, val percent: Int, val delta: Int?)
data class TopRow(val habit: Habit, val days: Int)

/** Everything the "Progress" dashboard at the top of the screen shows. */
data class DashboardState(
    val selectedDate: LocalDate = LocalDate.now(),
    val week: List<DashDay> = emptyList(),
    val progress: Float = 0f,
    val done: Int = 0,
    val total: Int = 0,
    val monthProgress: Float? = null,
    val topHabit: Pair<Habit, Int>? = null,
    val bestStreak: Int = 0,
    val bestStreakUnit: String = "days",
    val totalDays: Int = 0,
    val sheet: DashSheet? = null,
    val sheetYear: Int = LocalDate.now().year,
    val sheetMonth: Int = LocalDate.now().monthValue,
    val canGoNextYear: Boolean = false,
    val yearMonthly: List<Float?> = List(12) { null },
    val monthRows: List<HabitMonthRow> = emptyList(),
    val topByMonth: List<Pair<Habit, Int>?> = List(12) { null },
    val topRows: List<TopRow> = emptyList(),
)

data class HistoryUiState(
    val month: YearMonth = YearMonth.now(),
    val days: Map<LocalDate, HistoryDay> = emptyMap(),
    val leaderboard: List<LeaderEntry> = emptyList(),
    val mode: LeaderboardMode = LeaderboardMode.STREAK,
    val weekStart: DayOfWeek = DayOfWeek.MONDAY,
    val today: LocalDate = LocalDate.now(),
    val heatmap: Map<Long, Float> = emptyMap(),
    val yearPerfect: Int = 0,
    val dash: DashboardState = DashboardState(),
)

private data class DashControl(
    val selectedDate: LocalDate = LocalDate.now(),
    val sheet: DashSheet? = null,
    val year: Int = LocalDate.now().year,
    val month: Int = LocalDate.now().monthValue,
)

class HistoryViewModel(container: AppContainer) : ViewModel() {
    private val repo = container.repository
    private val month = MutableStateFlow(YearMonth.now())
    private val mode = MutableStateFlow(LeaderboardMode.STREAK)
    private val control = MutableStateFlow(DashControl())
    val uiState: StateFlow<HistoryUiState> = combine(repo.habits, repo.entries, container.settings.settings, month, mode) { habits, entries, settings, month, mode ->
        val logic = HabitLogic(if (settings.weekStartsMonday) DayOfWeek.MONDAY else DayOfWeek.SUNDAY)
        val today = LocalDate.now()
        val active = habits.filter { !it.archived }
        val days = (1..month.lengthOfMonth()).map { month.atDay(it) }.associateWith { d ->
            val (done, total) = logic.dayCounts(active, entries, d)
            HistoryDay(d, logic.dayProgress(active, entries, d), done, total)
        }
        val leaders = habits.map { h ->
            val s = logic.stats(h, entries, today)
            LeaderEntry(h, s.bestStreak, s.bestStreakDays, s.currentStreak, s.totalDays, s.streakUnit)
        }
        val ranked = leaders.sortedByDescending { if (mode == LeaderboardMode.STREAK) it.bestStreakDays else it.totalDays }.take(10)
        val heatmap = logic.overallHeatmap(active, entries, logic.graphStart(today), today)
        Partial(
            HistoryUiState(month, days, ranked, mode, logic.weekDays(today).first().dayOfWeek, today, heatmap, heatmap.values.count { it >= 0.999f }),
            logic, active, entries, leaders,
        )
    }.combine(control) { p, c -> p.ui.copy(dash = buildDash(p, c)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HistoryUiState())

    private data class Partial(
        val ui: HistoryUiState,
        val logic: HabitLogic,
        val active: List<Habit>,
        val entries: com.repdayrise.app.domain.EntryMap,
        val leaders: List<LeaderEntry>,
    )

    private fun buildDash(p: Partial, c: DashControl): DashboardState {
        val logic = p.logic
        val today = LocalDate.now()
        val date = c.selectedDate
        val week = logic.weekDays(date).map { DashDay(it, logic.dayProgress(p.active, p.entries, it), it == today, it.isAfter(today)) }
        val (done, total) = logic.dayCounts(p.active, p.entries, date)
        val dashMonth = YearMonth.from(date)
        val topHabit = p.active
            .map { it to logic.completedInRange(it, p.entries, dashMonth.atDay(1), dashMonth.atEndOfMonth()) }
            .filter { it.second > 0 }.maxByOrNull { it.second }
        val best = p.leaders.maxByOrNull { it.bestStreakDays }
        val activeDays = HashSet<Long>()
        p.active.forEach { h -> p.entries[h.id].orEmpty().forEach { (epoch, v) -> if (v > 0.0) activeDays.add(epoch) } }

        var yearMonthly = List<Float?>(12) { null }
        var monthRows = emptyList<HabitMonthRow>()
        var topByMonth = List<Pair<Habit, Int>?>(12) { null }
        var topRows = emptyList<TopRow>()
        if (c.sheet != null) {
            yearMonthly = (1..12).map { logic.monthProgress(p.active, p.entries, YearMonth.of(c.year, it), today) }
            val sheetMonth = YearMonth.of(c.year, c.month)
            val prev = sheetMonth.minusMonths(1)
            monthRows = p.active
                .filter { !sheetMonth.atDay(1).isAfter(today) && !it.startDate.isAfter(sheetMonth.atEndOfMonth()) }
                .map { h ->
                    val pct = (logic.monthCompletion(h, p.entries, sheetMonth, today) * 100).roundToInt()
                    val delta = if (!h.startDate.isAfter(prev.atEndOfMonth())) pct - (logic.monthCompletion(h, p.entries, prev, today) * 100).roundToInt() else null
                    HabitMonthRow(h, pct, delta)
                }.sortedByDescending { it.percent }
            topByMonth = (1..12).map { m ->
                val ym = YearMonth.of(c.year, m)
                if (ym.atDay(1).isAfter(today)) null
                else p.active.map { it to logic.completedInRange(it, p.entries, ym.atDay(1), ym.atEndOfMonth()) }.filter { it.second > 0 }.maxByOrNull { it.second }
            }
            topRows = p.active
                .map { TopRow(it, logic.completedInRange(it, p.entries, sheetMonth.atDay(1), sheetMonth.atEndOfMonth())) }
                .filter { it.days > 0 }.sortedByDescending { it.days }
        }
        return DashboardState(
            selectedDate = date, week = week, progress = logic.dayProgress(p.active, p.entries, date), done = done, total = total,
            monthProgress = logic.monthProgress(p.active, p.entries, dashMonth, today), topHabit = topHabit,
            bestStreak = best?.bestStreak ?: 0, bestStreakUnit = best?.unit ?: "days", totalDays = activeDays.size,
            sheet = c.sheet, sheetYear = c.year, sheetMonth = c.month, canGoNextYear = c.year < today.year,
            yearMonthly = yearMonthly, monthRows = monthRows, topByMonth = topByMonth, topRows = topRows,
        )
    }

    fun previousMonth() { month.value = month.value.minusMonths(1) }
    fun nextMonth() { if (month.value.isBefore(YearMonth.now())) month.value = month.value.plusMonths(1) }
    fun setMode(m: LeaderboardMode) { mode.value = m }

    fun selectDashDate(date: LocalDate) = control.update { it.copy(selectedDate = date) }
    fun openSheet(sheet: DashSheet) = control.update { it.copy(sheet = sheet, year = it.selectedDate.year, month = it.selectedDate.monthValue) }
    fun closeSheet() = control.update { it.copy(sheet = null) }
    fun shiftSheetYear(delta: Int) = control.update {
        val y = (it.year + delta).coerceAtMost(LocalDate.now().year)
        val m = if (y == LocalDate.now().year) it.month.coerceAtMost(LocalDate.now().monthValue) else it.month
        it.copy(year = y, month = m)
    }
    fun setSheetMonth(m: Int) = control.update { it.copy(month = m) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(viewModel: HistoryViewModel, onBack: () -> Unit, onOpenHabit: (Long) -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val dash = state.dash
    val sky = rememberSkyState(dash.progress, dash.selectedDate)
    val backdrop = rememberBackdrop()
    SystemBars(lightStatusIcons = true)
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()

    val statusPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val heroHeight = statusPadding + 300.dp
    val overlap = 30.dp

    Scaffold(
        modifier = Modifier.dayriseBackground(),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
    ) { _ ->
        Column(Modifier.fillMaxSize().verticalScroll(scroll)) {
            Box(Modifier.fillMaxWidth()) {
                // Hero: living sky, title and the glass progress pill.
                Box(Modifier.fillMaxWidth().height(heroHeight)) {
                    Box(Modifier.fillMaxSize().backdropSource(backdrop)) {
                        SkyScene(state = sky, hillFraction = 0.16f, modifier = Modifier.fillMaxSize())
                    }
                    Column(Modifier.fillMaxSize().padding(top = statusPadding)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onBack, colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                            }
                            Spacer(Modifier.weight(1f))
                            Icon(Icons.Rounded.WbTwilight, null, tint = Color.White, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("Progress", style = MaterialTheme.typography.titleLarge, color = Color.White)
                            Spacer(Modifier.weight(1f))
                            Spacer(Modifier.width(48.dp))
                        }
                        Spacer(Modifier.weight(1f))
                        ProgressPill(
                            done = dash.done,
                            percent = (dash.progress * 100).roundToInt(),
                            left = (dash.total - dash.done).coerceAtLeast(0),
                            modifier = Modifier
                                .padding(horizontal = 24.dp)
                                .padding(bottom = overlap + 26.dp)
                                .fillMaxWidth()
                                .frosted(backdrop, Color.White.copy(alpha = 0.16f), CircleShape)
                                .glassBorder(CircleShape, alpha = 0.45f),
                        )
                    }
                }

                // Panel over the bottom of the sky.
                Column(
                    Modifier
                        .padding(top = heroHeight - overlap)
                        .fillMaxWidth()
                        .frosted(backdrop, glassTint(0.9f), RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp))
                        .glassBorder(RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp), alpha = 0.10f)
                        .padding(horizontal = 16.dp)
                        .padding(top = 16.dp, bottom = 32.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
                ) {
                    GlowCard(shape = MaterialTheme.shapes.large) {
                        DayTileStrip(
                            days = dash.week, selected = dash.selectedDate, onSelect = { viewModel.selectDashDate(it) },
                            modifier = Modifier.padding(14.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val monthFraction = dash.monthProgress ?: 0f
                        DashTile("Month Progress", Modifier.weight(1f).fillMaxHeight(), onClick = { viewModel.openSheet(DashSheet.MONTH) }) {
                            TickGauge(monthFraction, Modifier.size(118.dp))
                            Spacer(Modifier.height(4.dp))
                            Text(
                                if (dash.monthProgress == null) "No data yet" else completionLevel(monthFraction),
                                style = MaterialTheme.typography.titleSmall,
                                color = if (dash.monthProgress == null) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF8BE34A),
                            )
                        }
                        val top = dash.topHabit
                        DashTile("Top Habit", Modifier.weight(1f).fillMaxHeight(), glow = top?.let { HabitColors.of(it.first.colorIndex) }, onClick = { viewModel.openSheet(DashSheet.TOP) }) {
                            if (top == null) {
                                Spacer(Modifier.weight(1f, fill = false).height(40.dp))
                                Text("Complete a habit to crown one", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                            } else {
                                val c = HabitColors.of(top.first.colorIndex)
                                Spacer(Modifier.height(14.dp))
                                Icon(HabitIcons[top.first.icon], null, tint = c, modifier = Modifier.size(54.dp))
                                Spacer(Modifier.height(14.dp))
                                Text(top.first.name, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 2)
                                Text("${top.second} ${if (top.second == 1) "day" else "days"}", style = MaterialTheme.typography.labelMedium, color = c)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        DashTile("Best Streak", Modifier.weight(1f).fillMaxHeight(), glow = MaterialTheme.colorScheme.primary) {
                            Text("${dash.bestStreak}", style = MaterialTheme.typography.displaySmall)
                            Text(if (dash.bestStreak == 1) dash.bestStreakUnit.removeSuffix("s") else dash.bestStreakUnit, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        DashTile("Active Days", Modifier.weight(1f).fillMaxHeight(), glow = MaterialTheme.colorScheme.secondary) {
                            Text("${dash.totalDays}", style = MaterialTheme.typography.displaySmall)
                            Text(if (dash.totalDays == 1) "day" else "days", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Spacer(Modifier.height(24.dp))
                    SectionTitle("Calendar")
                    Spacer(Modifier.height(10.dp))
                    CalendarSection(
                        state = state,
                        selectedDate = dash.selectedDate,
                        onPrevious = { viewModel.previousMonth() },
                        onNext = { viewModel.nextMonth() },
                        onPick = { viewModel.selectDashDate(it); scope.launch { scroll.animateScrollTo(0) } },
                    )
                    Spacer(Modifier.height(16.dp))
                    GlowCard(glow = MaterialTheme.colorScheme.primary) {
                        Column(Modifier.padding(16.dp)) {
                            SectionTitle("The past year")
                            Spacer(Modifier.height(12.dp))
                            ContributionGraph(
                                values = state.heatmap,
                                today = state.today,
                                weekStart = state.weekStart,
                                color = MaterialTheme.colorScheme.primary,
                                summary = "${state.yearPerfect} perfect ${if (state.yearPerfect == 1) "day" else "days"}",
                                describe = { date ->
                                    val pct = ((state.heatmap[date.toEpochDay()] ?: 0f) * 100).roundToInt()
                                    date.format(DateTimeFormatter.ofPattern("EEE, MMM d")) + " · " + if (pct == 0) "Nothing logged" else "$pct% of the day's habits"
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Leaderboard(state, viewModel, onOpenHabit)
                }
            }
        }
    }

    if (dash.sheet != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { viewModel.closeSheet() },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            when (dash.sheet) {
                DashSheet.MONTH -> MonthProgressSheet(dash, viewModel)
                DashSheet.TOP -> TopHabitSheet(dash, viewModel)
            }
        }
    }
}

@Composable
private fun SheetHeader(title: String, dash: DashboardState, viewModel: HistoryViewModel) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(40.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        IconButton(
            onClick = { viewModel.closeSheet() },
            colors = IconButtonDefaults.iconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
            modifier = Modifier.size(40.dp),
        ) { Icon(Icons.Rounded.Close, contentDescription = "Close", modifier = Modifier.size(20.dp)) }
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = { viewModel.shiftSheetYear(-1) }) { Icon(Icons.Rounded.ChevronLeft, contentDescription = "Previous year") }
        Text("${dash.sheetYear}", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
        IconButton(onClick = { viewModel.shiftSheetYear(1) }, enabled = dash.canGoNextYear) { Icon(Icons.Rounded.ChevronRight, contentDescription = "Next year") }
    }
}

@Composable
private fun MonthProgressSheet(dash: DashboardState, viewModel: HistoryViewModel) {
    val monthName = Month.of(dash.sheetMonth).getDisplayName(TextStyle.FULL, Locale.getDefault())
    val value = dash.yearMonthly.getOrNull(dash.sheetMonth - 1)
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        SheetHeader("Month Progress", dash, viewModel)
        MonthLineChart(
            values = dash.yearMonthly, selected = dash.sheetMonth - 1, onSelect = { viewModel.setSheetMonth(it + 1) },
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(monthName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            if (value != null) {
                Text("${(value * 100).roundToInt()}%", style = MaterialTheme.typography.titleMedium, color = levelColor(value))
                Spacer(Modifier.width(6.dp))
                Text(completionLevel(value), style = MaterialTheme.typography.bodyMedium, color = levelColor(value))
            } else {
                Text("No data", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 4.dp)) {
            Text("HABIT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("PROGRESS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.fillMaxWidth().height(1.dp).padding(horizontal = 20.dp).border(0.5.dp, MaterialTheme.colorScheme.outlineVariant))
        if (dash.monthRows.isEmpty()) {
            Text("Nothing to show for this month.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
        }
        dash.monthRows.forEach { row ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBadge(HabitIcons[row.habit.icon], HabitColors.of(row.habit.colorIndex), size = 34.dp, iconSize = 18.dp)
                Spacer(Modifier.width(14.dp))
                Text(row.habit.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1)
                Column(horizontalAlignment = Alignment.End) {
                    Text("${row.percent}%", style = MaterialTheme.typography.titleMedium)
                    row.delta?.let { d ->
                        val up = d >= 0
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                if (d == 0) "—" else "${if (up) "+" else ""}$d%",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (d == 0) MaterialTheme.colorScheme.onSurfaceVariant else if (up) Color(0xFF3DDC84) else MaterialTheme.colorScheme.error,
                            )
                            if (d != 0) Icon(if (up) Icons.Rounded.NorthEast else Icons.Rounded.SouthEast, null, tint = if (up) Color(0xFF3DDC84) else MaterialTheme.colorScheme.error, modifier = Modifier.size(11.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TopHabitSheet(dash: DashboardState, viewModel: HistoryViewModel) {
    val monthName = Month.of(dash.sheetMonth).getDisplayName(TextStyle.FULL, Locale.getDefault())
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 32.dp)) {
        SheetHeader("Top Habit", dash, viewModel)
        Spacer(Modifier.height(12.dp))
        FlameBarChart(
            days = dash.topByMonth, selected = dash.sheetMonth - 1, onSelect = { viewModel.setSheetMonth(it + 1) },
            modifier = Modifier.padding(horizontal = 20.dp),
        )
        Text(monthName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp))
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 4.dp)) {
            Text("#", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(30.dp))
            Text("HABIT", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Text("DAYS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.fillMaxWidth().height(1.dp).padding(horizontal = 20.dp).border(0.5.dp, MaterialTheme.colorScheme.outlineVariant))
        if (dash.topRows.isEmpty()) {
            Text("No completed days this month.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(20.dp))
        }
        dash.topRows.forEachIndexed { i, row ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(30.dp))
                IconBadge(HabitIcons[row.habit.icon], HabitColors.of(row.habit.colorIndex), size = 34.dp, iconSize = 18.dp)
                Spacer(Modifier.width(14.dp))
                Text(row.habit.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1)
                Text("${row.days}", style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

@Composable
private fun CalendarSection(state: HistoryUiState, selectedDate: LocalDate, onPrevious: () -> Unit, onNext: () -> Unit, onPick: (LocalDate) -> Unit) {
    GlowCard {
        Column(Modifier.padding(14.dp)) {
            MonthCalendar(
                month = state.month,
                weekStart = state.weekStart,
                onPrevious = onPrevious,
                onNext = onNext,
                canGoNext = state.month.isBefore(YearMonth.now()),
            ) { date ->
                if (date == null) { Box(Modifier.size(42.dp)); return@MonthCalendar }
                val day = state.days[date]
                val future = date.isAfter(state.today)
                val isSelected = selectedDate == date
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(40.dp)
                            .border(2.dp, if (isSelected) MaterialTheme.colorScheme.primary else if (date == state.today) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else Color.Transparent, CircleShape)
                            .padding(3.dp)
                            .clip(CircleShape)
                            .clickable(enabled = !future) { onPick(date) }
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
        }
    }
}

@Composable
private fun Leaderboard(state: HistoryUiState, viewModel: HistoryViewModel, onOpenHabit: (Long) -> Unit) {
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
                    val value = if (state.mode == LeaderboardMode.STREAK) countLabel(e.bestStreak, e.unit) else countLabel(e.totalDays, "days")
                    Text(value, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}
