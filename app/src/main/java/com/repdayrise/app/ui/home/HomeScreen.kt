package com.repdayrise.app.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.FormatListBulleted
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repdayrise.app.data.model.HabitType
import com.repdayrise.app.data.model.ScheduleType
import com.repdayrise.app.data.model.amountLabel
import com.repdayrise.app.data.model.formatDuration
import com.repdayrise.app.data.model.formatValue
import com.repdayrise.app.ui.components.DayTitle
import com.repdayrise.app.ui.components.FloatingBarButton
import com.repdayrise.app.ui.components.FloatingBarClearance
import com.repdayrise.app.ui.components.FloatingBarPill
import com.repdayrise.app.ui.components.GlassCircleButton
import com.repdayrise.app.ui.components.HabitControl
import com.repdayrise.app.ui.components.HabitIcons
import com.repdayrise.app.ui.components.IconBadge
import com.repdayrise.app.ui.components.SunrisePill
import com.repdayrise.app.ui.components.SystemBars
import com.repdayrise.app.ui.components.glassBorder
import com.repdayrise.app.ui.history.HistoryViewModel
import com.repdayrise.app.ui.history.ProgressView
import com.repdayrise.app.ui.theme.HabitColors
import com.repdayrise.app.ui.theme.LocalIsDark
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** The two faces of home, switched from the floating bar: the sky dashboard and the habit list. */
enum class HomeMode { SKY, LIST }

/** What the list shows: the selected day's due habits, or every habit with its schedule. */
enum class ListFilter { DAY, ALL }

private val FlameColor = Color(0xFFFF6B3D)

@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    progressViewModel: HistoryViewModel,
    skyRequest: Int,
    onOpenHabit: (Long) -> Unit,
    onAddHabit: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPartners: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    var valueSheetFor by remember { mutableStateOf<HabitRowUi?>(null) }
    var mode by rememberSaveable { mutableStateOf(HomeMode.LIST) }
    var filter by rememberSaveable { mutableStateOf(ListFilter.DAY) }

    LifecycleResumeEffect(Unit) {
        viewModel.refreshToday()
        viewModel.syncHealth()
        onPauseOrDispose { }
    }
    // The widget's "history" tap lands here and asks for the sky.
    LaunchedEffect(skyRequest) { if (skyRequest > 0) mode = HomeMode.SKY }
    // Home owns the selected day; the dashboard follows it.
    LaunchedEffect(state.selectedDate) { progressViewModel.selectDashDate(state.selectedDate) }
    BackHandler(enabled = mode == HomeMode.SKY) { mode = HomeMode.LIST }

    val bottomInset = FloatingBarClearance + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Crossfade(targetState = mode, animationSpec = tween(320), label = "home-mode") { m ->
            when (m) {
                HomeMode.SKY -> ProgressView(
                    viewModel = progressViewModel,
                    onOpenHabit = onOpenHabit,
                    onOpenSettings = onOpenSettings,
                    onOpenPartners = onOpenPartners,
                    onSelectDate = { viewModel.selectDate(it) },
                    bottomInset = bottomInset,
                )
                HomeMode.LIST -> HabitList(
                    state = state,
                    filter = filter,
                    listState = listState,
                    bottomInset = bottomInset,
                    onOpenSettings = onOpenSettings,
                    onAddHabit = onAddHabit,
                    onOpenHabit = onOpenHabit,
                    onToggleGroup = { section -> section.group?.let { viewModel.setGroupCollapsed(it, !section.collapsed) } },
                    onPrimary = { row ->
                        if (state.isFuture) return@HabitList
                        when (row.habit.type) {
                            HabitType.CHECK -> {
                                if (state.haptics) haptic.performHapticFeedback(if (row.completed) HapticFeedbackType.ToggleOff else HapticFeedbackType.Confirm)
                                viewModel.toggle(row.habit)
                            }
                            HabitType.COUNT -> {
                                if (row.completed) {
                                    if (state.haptics) haptic.performHapticFeedback(HapticFeedbackType.ToggleOff)
                                    viewModel.setValue(row.habit, 0.0)
                                } else {
                                    if (state.haptics) haptic.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                    viewModel.increment(row.habit, 1.0)
                                }
                            }
                            HabitType.TIMER -> {
                                if (row.completed) {
                                    if (state.haptics) haptic.performHapticFeedback(HapticFeedbackType.ToggleOff)
                                    viewModel.setValue(row.habit, 0.0)
                                } else {
                                    if (state.haptics) haptic.performHapticFeedback(HapticFeedbackType.Confirm)
                                    viewModel.toggleTimer(row.habit, row.timer)
                                }
                            }
                        }
                    },
                    onLong = { row ->
                        if (state.isFuture) return@HabitList
                        if (state.haptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        valueSheetFor = row
                    },
                    onFinishTimer = { viewModel.finishTimer(it.habit) },
                )
            }
        }
        FloatingBar(
            mode = mode,
            filter = filter,
            selectedDate = state.selectedDate,
            today = state.today,
            onMode = { mode = it },
            onPickDay = { date -> viewModel.selectDate(date); filter = ListFilter.DAY },
            onPickAll = { filter = ListFilter.ALL; mode = HomeMode.LIST },
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    valueSheetFor?.let { row ->
        ValueSheet(
            row = row,
            onDismiss = { valueSheetFor = null },
            onSet = { v -> viewModel.setValue(row.habit, v); valueSheetFor = null },
            onCancelTimer = { viewModel.cancelTimer(row.habit); valueSheetFor = null },
        )
    }
}

/** Header tint: indigo warming to green as the day fills up, or violet for the all-habits list. */
private fun headerTint(filter: ListFilter, progress: Float): Color = when (filter) {
    ListFilter.DAY -> lerp(Color(0xFF2E3482), Color(0xFF1F7A4D), progress.coerceIn(0f, 1f))
    ListFilter.ALL -> Color(0xFF5A2A88)
}

@Composable
private fun HabitList(
    state: HomeUiState,
    filter: ListFilter,
    listState: LazyListState,
    bottomInset: Dp,
    onOpenSettings: () -> Unit,
    onAddHabit: () -> Unit,
    onOpenHabit: (Long) -> Unit,
    onToggleGroup: (HomeSection) -> Unit,
    onPrimary: (HabitRowUi) -> Unit,
    onLong: (HabitRowUi) -> Unit,
    onFinishTimer: (HabitRowUi) -> Unit,
) {
    SystemBars(lightStatusIcons = true)
    val statusPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val barHeight = statusPadding + 68.dp
    val bg = MaterialTheme.colorScheme.background
    val tint by animateColorAsState(headerTint(filter, state.progress), tween(600), label = "header")
    val top = lerp(bg, tint, 0.85f)

    Box(
        Modifier.fillMaxSize().drawBehind {
            drawRect(bg)
            drawRect(Brush.verticalGradient(0f to top, 1f to bg, endY = (barHeight + 220.dp).toPx()))
        },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = barHeight + 6.dp, bottom = bottomInset + 16.dp),
        ) {
            if (!state.hasAnyHabits && state.loaded) {
                item(key = "empty") { EmptyState(onAddHabit) }
            } else when (filter) {
                ListFilter.DAY -> {
                    if (state.sections.isEmpty() && state.loaded) {
                        item(key = "nothing") {
                            Text(
                                if (state.isFuture) "Nothing scheduled yet" else "Nothing due today. Enjoy the calm.",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth().padding(32.dp),
                            )
                        }
                    }
                    state.sections.forEach { section ->
                        if (section.group != null) {
                            item(key = "group-${section.group.id}") {
                                GroupHeader(
                                    name = section.group.name,
                                    done = section.rows.count { it.completed },
                                    total = section.rows.size,
                                    collapsed = section.collapsed,
                                    onToggle = { onToggleGroup(section) },
                                    modifier = Modifier.animateItem(),
                                )
                            }
                        }
                        if (!section.collapsed) {
                            items(section.rows, key = { "habit-${it.habit.id}" }) { row ->
                                HabitRow(
                                    row = row,
                                    editable = !state.isFuture,
                                    modifier = Modifier
                                        .animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.8f))
                                        .padding(horizontal = 16.dp, vertical = 5.dp),
                                    onOpen = { onOpenHabit(row.habit.id) },
                                    onPrimary = { onPrimary(row) },
                                    onLong = { onLong(row) },
                                    onFinishTimer = { onFinishTimer(row) },
                                )
                            }
                        }
                    }
                }
                ListFilter.ALL -> items(state.allHabits, key = { "all-${it.habit.id}" }) { row ->
                    AllHabitCard(
                        row = row,
                        today = state.today,
                        onOpen = { onOpenHabit(row.habit.id) },
                        modifier = Modifier.animateItem().padding(horizontal = 16.dp, vertical = 5.dp),
                    )
                }
            }
        }

        // Title bar: the list scrolls up under the header tint and fades out beneath it.
        Row(
            Modifier
                .fillMaxWidth()
                .height(barHeight)
                .background(Brush.verticalGradient(0f to top, 0.72f to top, 1f to top.copy(alpha = 0f)))
                .padding(top = statusPadding)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlassCircleButton(Icons.Rounded.Tune, "Settings", onOpenSettings)
            when (filter) {
                ListFilter.DAY -> DayTitle(
                    state.selectedDate, state.today, Modifier.weight(1f),
                    trailing = if (state.total > 0 && state.done >= state.total) {
                        { Icon(Icons.Rounded.CheckCircle, contentDescription = "All done", tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(20.dp)) }
                    } else null,
                )
                ListFilter.ALL -> Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Text("All Habits", style = MaterialTheme.typography.titleLarge, color = Color.White)
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(22.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                        Text("${state.allHabits.size}", style = MaterialTheme.typography.labelMedium, color = Color.White)
                    }
                }
            }
            GlassCircleButton(Icons.Rounded.Add, "New habit", onAddHabit)
        }
    }
}

@Composable
private fun FloatingBar(
    mode: HomeMode,
    filter: ListFilter,
    selectedDate: LocalDate,
    today: LocalDate,
    onMode: (HomeMode) -> Unit,
    onPickDay: (LocalDate) -> Unit,
    onPickAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val showingAll = mode == HomeMode.LIST && filter == ListFilter.ALL
    Row(
        modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FloatingBarButton(Icons.Rounded.BarChart, "Progress", selected = mode == HomeMode.SKY, onClick = { onMode(HomeMode.SKY) })
        Spacer(Modifier.weight(1f))
        Box {
            FloatingBarPill(
                icon = if (showingAll) Icons.AutoMirrored.Rounded.FormatListBulleted else Icons.Rounded.WbSunny,
                label = if (showingAll) "All Habits" else shortDay(selectedDate, today),
                onClick = { menu = true },
            )
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, shape = RoundedCornerShape(20.dp)) {
                DropdownMenuItem(
                    text = { Text("Today") },
                    leadingIcon = { Icon(Icons.Rounded.WbSunny, contentDescription = null) },
                    onClick = { menu = false; onPickDay(today) },
                )
                DropdownMenuItem(
                    text = { Text("Yesterday") },
                    leadingIcon = { Icon(Icons.Rounded.History, contentDescription = null) },
                    onClick = { menu = false; onPickDay(today.minusDays(1)) },
                )
                DropdownMenuItem(
                    text = { Text("All Habits") },
                    leadingIcon = { Icon(Icons.AutoMirrored.Rounded.FormatListBulleted, contentDescription = null) },
                    onClick = { menu = false; onPickAll() },
                )
            }
        }
        Spacer(Modifier.weight(1f))
        FloatingBarButton(Icons.AutoMirrored.Rounded.FormatListBulleted, "Habits", selected = mode == HomeMode.LIST, onClick = { onMode(HomeMode.LIST) })
    }
}

private fun shortDay(date: LocalDate, today: LocalDate): String = when (date) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    today.plusDays(1) -> "Tomorrow"
    else -> date.format(DateTimeFormatter.ofPattern("EEE d"))
}

/** `Today`, `Tomorrow, Oct 28`, `Friday, Oct 31` or `Nov 14` for the next time a habit is due. */
private fun nextDueLabel(date: LocalDate?, today: LocalDate): String {
    if (date == null) return ""
    val days = ChronoUnit.DAYS.between(today, date)
    return when {
        days == 0L -> "Today"
        days == 1L -> "Tomorrow, " + date.format(DateTimeFormatter.ofPattern("MMM d"))
        days < 7 -> date.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))
        else -> date.format(DateTimeFormatter.ofPattern("MMM d"))
    }
}

/** The streak chip number, with a short unit suffix for weekly and monthly habits so "4" can't be mistaken for days. */
private fun streakText(streak: Int, unit: String): String = when (unit) {
    "weeks" -> "${streak}w"
    "months" -> "${streak}mo"
    else -> "$streak"
}

@Composable
private fun GroupHeader(name: String, done: Int, total: Int, collapsed: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val rotation by animateFloatAsState(if (collapsed) -90f else 0f, label = "chev")
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(8.dp))
        Text("$done/$total", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Icon(Icons.Rounded.ExpandMore, contentDescription = if (collapsed) "Expand" else "Collapse", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.rotate(rotation))
    }
}

/** Flat card surface shared by both lists: the base surface with a faint wash of the habit colour from the left. */
@Composable
private fun Modifier.habitCard(color: Color): Modifier {
    val shape = RoundedCornerShape(20.dp)
    val surface = MaterialTheme.colorScheme.surfaceContainer
    val dark = LocalIsDark.current
    return this
        .fillMaxWidth()
        .clip(shape)
        .drawBehind {
            drawRect(surface)
            drawRect(Brush.horizontalGradient(0f to color.copy(alpha = if (dark) 0.10f else 0.08f), 0.6f to color.copy(alpha = 0f)))
        }
        .glassBorder(shape, alpha = if (dark) 0.05f else 0.6f)
}

@Composable
private fun HabitName(name: String, streak: Int, streakUnit: String, completed: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            name,
            style = MaterialTheme.typography.titleMedium,
            color = if (completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            textDecoration = if (completed) TextDecoration.LineThrough else null,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (streak > 0) {
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.LocalFireDepartment, contentDescription = null, tint = FlameColor, modifier = Modifier.size(14.dp))
            Text(streakText(streak, streakUnit), style = MaterialTheme.typography.labelMedium, color = FlameColor)
        }
    }
}

@Composable
fun HabitRow(
    row: HabitRowUi,
    editable: Boolean,
    onOpen: () -> Unit,
    onPrimary: () -> Unit,
    onLong: () -> Unit,
    onFinishTimer: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val habit = row.habit
    val color = HabitColors.of(habit.colorIndex)
    // Live timer ticking
    var elapsed by remember(row.timer?.startedAt, row.timer?.running) { mutableStateOf(row.timer?.elapsedMs() ?: 0L) }
    LaunchedEffect(row.timer?.running, row.timer?.startedAt) {
        while (row.timer?.running == true) {
            elapsed = row.timer.elapsedMs()
            delay(1000)
        }
        elapsed = row.timer?.elapsedMs() ?: 0L
    }
    val timerMinutes = elapsed / 60000.0
    val liveFraction = if (habit.type == HabitType.TIMER && row.timer != null) ((row.value + timerMinutes) / habit.effectiveGoal).toFloat().coerceIn(0f, 1f) else row.fraction

    val recurring = habit.scheduleType != ScheduleType.DAILY
    val period = if (habit.scheduleType == ScheduleType.WEEKLY) "week" else "month"
    val todayValue = when (habit.type) {
        HabitType.CHECK -> null
        HabitType.COUNT -> "${formatValue(row.value)} / ${amountLabel(habit.goal, habit.unit)}".trim()
        HabitType.TIMER -> if (row.timer != null && row.timer.running) {
            "● " + formatClock(elapsed) + " · goal ${formatDuration(habit.goal)}"
        } else if (row.timer != null) {
            "Paused " + formatClock(elapsed) + " · goal ${formatDuration(habit.goal)}"
        } else "${formatDuration(row.value)} / ${formatDuration(habit.goal)}"
    }
    // Plain daily check-offs stay a single line, like Dayrise; anything with an amount or a period shows it underneath.
    val subtitle = when {
        !recurring -> todayValue
        todayValue == null -> "${row.periodDone}/${row.periodGoal} this $period"
        row.value > 0.0 || row.timer != null -> "${row.periodDone}/${row.periodGoal} this $period · $todayValue"
        else -> "${row.periodDone}/${row.periodGoal} this $period"
    }

    Row(
        modifier
            .habitCard(color)
            .clickable(onClick = onOpen)
            .padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(HabitIcons[habit.icon], contentDescription = null, tint = if (row.completed) color.copy(alpha = 0.55f) else color, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            HabitName(habit.name, row.streak, row.streakUnit, row.completed)
            if (subtitle != null) {
                Spacer(Modifier.height(2.dp))
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (row.timer?.running == true) color else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (habit.type == HabitType.TIMER && row.timer != null && !row.completed) {
            TextButton(onClick = onFinishTimer, contentPadding = PaddingValues(horizontal = 10.dp)) { Text("Finish", color = color) }
        }
        HabitControl(
            type = habit.type,
            completed = row.completed,
            fraction = liveFraction,
            colorIndex = habit.colorIndex,
            timerRunning = row.timer?.running == true,
            onClick = onPrimary,
            onLongClick = onLong,
            size = 40.dp,
            modifier = Modifier.graphicsLayer { alpha = if (editable) 1f else 0.4f },
        )
    }
}

/** Card in the all-habits list: name and streak on top, `⟳ Every day … Tomorrow, Oct 28` along a hairline footer. */
@Composable
private fun AllHabitCard(row: AllHabitRow, today: LocalDate, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val habit = row.habit
    val color = HabitColors.of(habit.colorIndex)
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.habitCard(color).clickable(onClick = onOpen)) {
        Row(Modifier.padding(start = 18.dp, end = 16.dp, top = 16.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(HabitIcons[habit.icon], contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            HabitName(habit.name, row.streak, row.streakUnit, completed = false)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
        Row(Modifier.padding(horizontal = 18.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Repeat, contentDescription = null, tint = muted, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(habit.scheduleLabel(), style = MaterialTheme.typography.labelMedium, color = muted, maxLines = 1)
            Spacer(Modifier.weight(1f))
            Text(nextDueLabel(row.nextDue, today), style = MaterialTheme.typography.labelMedium, color = muted, maxLines = 1)
        }
    }
}

fun formatClock(ms: Long): String {
    val totalSec = ms / 1000
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}

@Composable
private fun EmptyState(onAdd: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(84.dp).clip(CircleShape).background(
                Brush.verticalGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.tertiaryContainer)),
            ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.WbSunny, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text("Your sky is still dark", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(
            "Add a habit and every completion lifts the sun a little higher.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(18.dp))
        Button(
            onClick = onAdd,
            shape = CircleShape,
            colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent, contentColor = Color.White),
            modifier = Modifier.background(SunrisePill, CircleShape),
        ) {
            Icon(Icons.Rounded.Add, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("Create your first habit")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ValueSheet(row: HabitRowUi, onDismiss: () -> Unit, onSet: (Double) -> Unit, onCancelTimer: () -> Unit) {
    val habit = row.habit
    val color = HabitColors.of(habit.colorIndex)
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var text by remember { mutableStateOf(if (row.value == 0.0) "" else formatValue(row.value)) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 28.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(HabitIcons[habit.icon], color, size = 40.dp, iconSize = 20.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(habit.name, style = MaterialTheme.typography.titleLarge)
                    Text(
                        when (habit.type) {
                            HabitType.CHECK -> "Update this day"
                            HabitType.COUNT -> "Goal ${amountLabel(habit.goal, habit.unit)}".trim()
                            HabitType.TIMER -> "Goal ${formatDuration(habit.goal)}"
                        },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            when (habit.type) {
                HabitType.CHECK -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { onSet(1.0) }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = color)) { Text("Mark done") }
                        FilledTonalButton(onClick = { onSet(0.0) }, modifier = Modifier.weight(1f)) { Text("Clear") }
                    }
                }
                else -> {
                    val unitLabel = if (habit.type == HabitType.TIMER) "minutes" else habit.unit.ifBlank { "times" }
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text("Value ($unitLabel)") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium,
                    )
                    Spacer(Modifier.height(12.dp))
                    val quick = if (habit.type == HabitType.TIMER) listOf(5.0, 10.0, 15.0, 30.0) else listOf(1.0, 2.0, 5.0, 10.0)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        quick.forEach { q ->
                            FilledTonalButton(onClick = { text = formatValue(row.value + q) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) {
                                Text("+${formatValue(q)}")
                            }
                        }
                    }
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = { onSet(text.toDoubleOrNull() ?: 0.0) },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = color),
                        ) { Text("Save") }
                        FilledTonalButton(onClick = { onSet(habit.goal) }, modifier = Modifier.weight(1f)) { Text("Complete") }
                    }
                    if (habit.type == HabitType.TIMER && row.timer != null) {
                        Spacer(Modifier.height(8.dp))
                        TextButton(onClick = onCancelTimer, modifier = Modifier.fillMaxWidth()) { Text("Discard running timer", color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        }
    }
}
