package com.repdayrise.app.ui.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repdayrise.app.data.model.Habit
import com.repdayrise.app.data.model.HabitType
import com.repdayrise.app.data.model.ScheduleType
import com.repdayrise.app.data.model.formatDuration
import com.repdayrise.app.data.model.formatValue
import com.repdayrise.app.ui.components.Backdrop
import com.repdayrise.app.ui.components.HabitControl
import com.repdayrise.app.ui.components.SkyState
import com.repdayrise.app.ui.components.backdropSource
import com.repdayrise.app.ui.components.dayriseBackground
import com.repdayrise.app.ui.components.drawSky
import com.repdayrise.app.ui.components.frosted
import com.repdayrise.app.ui.components.glassBorder
import com.repdayrise.app.ui.components.glassTint
import com.repdayrise.app.ui.components.rememberBackdrop
import com.repdayrise.app.ui.components.rememberSkyState
import com.repdayrise.app.ui.components.SunrisePill
import com.repdayrise.app.ui.theme.LocalIsDark
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.material3.FloatingActionButtonDefaults
import com.repdayrise.app.ui.components.HabitIcons
import com.repdayrise.app.ui.components.IconBadge
import com.repdayrise.app.ui.components.MiniSunrise
import com.repdayrise.app.ui.components.SkyScene
import com.repdayrise.app.ui.components.SystemBars
import com.repdayrise.app.ui.theme.HabitColors
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Suppress("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenHabit: (Long) -> Unit,
    onAddHabit: () -> Unit,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current
    var valueSheetFor by remember { mutableStateOf<HabitRowUi?>(null) }

    val backdrop = rememberBackdrop()
    val sky = rememberSkyState(state.progress, state.selectedDate)
    val isDark = LocalIsDark.current

    LifecycleResumeEffect(Unit) {
        viewModel.refreshToday()
        viewModel.syncHealth()
        onPauseOrDispose { }
    }

    val heroHeight = 372.dp
    val stripOverlap = 64.dp
    val statusPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val scrolled by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 40 } }
    val pinned by remember { derivedStateOf { listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 260 } }
    SystemBars(lightStatusIcons = !pinned || isDark)

    Scaffold(
        modifier = Modifier.dayriseBackground(),
        containerColor = Color.Transparent,
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onAddHabit,
                expanded = !scrolled,
                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                text = { Text("New habit") },
                containerColor = Color.Transparent,
                contentColor = Color.White,
                elevation = FloatingActionButtonDefaults.elevation(0.dp, 0.dp, 0.dp, 0.dp),
                modifier = Modifier
                    .shadow(18.dp, RoundedCornerShape(18.dp), ambientColor = MaterialTheme.colorScheme.primary, spotColor = MaterialTheme.colorScheme.primary)
                    .background(SunrisePill, RoundedCornerShape(18.dp)),
            )
        },
        contentWindowInsets = WindowInsets.navigationBars,
    ) { _ ->
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().backdropSource(backdrop),
                contentPadding = PaddingValues(bottom = 120.dp + WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()),
            ) {
                item(key = "hero") {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(heroHeight + statusPadding)
                            .graphicsLayer {
                                val offset = if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset else 0
                                translationY = offset * 0.55f
                            },
                    ) {
                        SkyScene(state = sky, modifier = Modifier.fillMaxSize(), hillFraction = 0.2f) {
                            HeroOverlay(state, statusPadding, stripOverlap, onOpenHistory, onOpenSettings, onToday = { viewModel.goToday() })
                        }
                    }
                }
                item(key = "week") {
                    WeekStrip(
                        state = state,
                        sky = sky,
                        listState = listState,
                        heroHeight = heroHeight + statusPadding,
                        overlap = stripOverlap,
                        onSelect = { viewModel.selectDate(it) },
                        onSwipe = { viewModel.shiftWeek(it) },
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .offset(y = -stripOverlap),
                    )
                }
                if (!state.hasAnyHabits && state.loaded) {
                    item(key = "empty") { EmptyState(onAddHabit) }
                } else if (state.sections.isEmpty() && state.loaded) {
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
                                onToggle = { viewModel.setGroupCollapsed(section.group, !section.collapsed) },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                    if (!section.collapsed) {
                        items(section.rows.size, key = { "habit-${section.rows[it].habit.id}" }) { i ->
                            val row = section.rows[i]
                            HabitRow(
                                row = row,
                                editable = !state.isFuture,
                                modifier = Modifier
                                    .animateItem(fadeInSpec = null, fadeOutSpec = null, placementSpec = spring(stiffness = Spring.StiffnessMediumLow, dampingRatio = 0.8f))
                                    .padding(horizontal = 16.dp, vertical = 5.dp),
                                onOpen = { onOpenHabit(row.habit.id) },
                                onPrimary = {
                                    if (state.isFuture) return@HabitRow
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
                                onLong = {
                                    if (state.isFuture) return@HabitRow
                                    if (state.haptics) haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    valueSheetFor = row
                                },
                                onFinishTimer = { viewModel.finishTimer(row.habit) },
                            )
                        }
                    }
                }
            }
            PinnedHeader(
                visible = pinned,
                state = state,
                backdrop = backdrop,
                statusPadding = statusPadding,
                onOpenHistory = onOpenHistory,
                onOpenSettings = onOpenSettings,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
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

@Composable
private fun HeroOverlay(state: HomeUiState, statusPadding: Dp, stripOverlap: Dp, onOpenHistory: () -> Unit, onOpenSettings: () -> Unit, onToday: () -> Unit) {
    val dateLabel = remember(state.selectedDate, state.today) {
        when (state.selectedDate) {
            state.today -> "Today"
            state.today.minusDays(1) -> "Yesterday"
            state.today.plusDays(1) -> "Tomorrow"
            else -> state.selectedDate.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))
        }
    }
    val subtitle = remember(state.selectedDate, state.today) {
        if (state.selectedDate == state.today) state.today.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")) else greetingFor(state.selectedDate, state.today)
    }
    Column(Modifier.fillMaxSize().padding(top = statusPadding)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                Text(dateLabel, style = MaterialTheme.typography.titleLarge, color = Color.White)
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.78f))
            }
            IconButton(onClick = onOpenHistory, colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)) {
                Icon(Icons.Rounded.CalendarMonth, contentDescription = "Sunrise history")
            }
            IconButton(onClick = onOpenSettings, colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)) {
                Icon(Icons.Rounded.Settings, contentDescription = "Settings")
            }
        }
        Spacer(Modifier.weight(1f))
        Column(Modifier.padding(start = 24.dp, bottom = stripOverlap + 22.dp)) {
            AnimatedContent(
                targetState = (state.progress * 100).roundToInt(),
                transitionSpec = { (slideInVertically { it / 2 } + fadeIn()) togetherWith (slideOutVertically { -it / 2 } + fadeOut()) },
                label = "pct",
            ) { pct ->
                Text("$pct%", style = MaterialTheme.typography.displayLarge, color = Color.White)
            }
            val message = when {
                state.total == 0 -> "Add a habit to start the day"
                state.done == state.total -> "The sun is up. Beautiful work."
                state.done == 0 -> "${state.total} ${if (state.total == 1) "habit" else "habits"} waiting for you"
                else -> "${state.done} of ${state.total} done · keep rising"
            }
            Text(message, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.9f))
            AnimatedVisibility(visible = state.selectedDate != state.today, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                Surface(
                    onClick = onToday,
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.18f),
                    contentColor = Color.White,
                    modifier = Modifier.padding(top = 12.dp).glassBorder(CircleShape, alpha = 0.35f),
                ) {
                    Text("Back to today", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                }
            }
        }
    }
}

private fun greetingFor(date: LocalDate, today: LocalDate): String {
    val diff = java.time.temporal.ChronoUnit.DAYS.between(today, date)
    return when {
        diff < -1 -> "${-diff} days ago"
        diff > 1 -> "In $diff days"
        else -> date.format(DateTimeFormatter.ofPattern("MMMM d"))
    }
}

fun greeting(): String {
    val h = LocalTime.now().hour
    return when {
        h < 5 -> "Still night"
        h < 12 -> "Good morning"
        h < 17 -> "Good afternoon"
        h < 21 -> "Good evening"
        else -> "Good night"
    }
}

@Composable
private fun WeekStrip(
    state: HomeUiState,
    sky: SkyState,
    listState: LazyListState,
    heroHeight: Dp,
    overlap: Dp,
    onSelect: (LocalDate) -> Unit,
    onSwipe: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(26.dp)
    val dark = LocalIsDark.current
    val pageBg = MaterialTheme.colorScheme.background
    val tint = if (dark) Color(0xFF141A33).copy(alpha = 0.55f) else Color.White.copy(alpha = 0.62f)
    Box(
        modifier
            .fillMaxWidth()
            .shadow(14.dp, shape, ambientColor = Color.Black.copy(alpha = 0.35f), spotColor = Color.Black.copy(alpha = 0.35f))
            .clip(shape)
            .pointerInput(Unit) {
                var total = 0f
                detectHorizontalDragGestures(
                    onDragStart = { total = 0f },
                    onDragEnd = { if (abs(total) > 80f) onSwipe(if (total < 0) 1 else -1) },
                ) { _, drag -> total += drag }
            },
    ) {
        // Blurred echo of the sky exactly where the card overlaps the hero, so the card reads as glass.
        Box(
            Modifier
                .matchParentSize()
                .graphicsLayer { renderEffect = BlurEffect(22.dp.toPx(), 22.dp.toPx(), TileMode.Clamp) }
                .drawBehind {
                    val parallax = if (listState.firstVisibleItemIndex == 0) listState.firstVisibleItemScrollOffset * 0.55f else 0f
                    val heroH = heroHeight.toPx()
                    val heroW = size.width + 32.dp.toPx()
                    val dx = -16.dp.toPx()
                    val dy = -(heroH - overlap.toPx() - parallax)
                    translate(dx, dy) {
                        drawSky(sky.progress, sky.moon, time = 0f, stars = emptyList(), clouds = emptyList(), hillFraction = 0.2f, detail = false, sizeOverride = Size(heroW, heroH))
                        drawRect(pageBg, topLeft = Offset(0f, heroH), size = Size(heroW, heroH))
                    }
                },
        )
        Box(Modifier.matchParentSize().background(tint).drawBehind {
            drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = if (dark) 0.06f else 0.35f), 1f to Color.Transparent, endY = size.height * 0.5f))
        })
        Box(Modifier.matchParentSize().glassBorder(shape))
        Row(Modifier.padding(horizontal = 8.dp, vertical = 12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            state.week.forEach { day ->
                val selected = day.date == state.selectedDate
                val borderColor = when {
                    selected -> MaterialTheme.colorScheme.primary
                    else -> Color.Transparent
                }
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { onSelect(day.date) }
                        .padding(vertical = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        day.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (day.isToday) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (day.isToday) FontWeight.ExtraBold else FontWeight.SemiBold,
                    )
                    Spacer(Modifier.height(6.dp))
                    Box(
                        Modifier
                            .size(40.dp)
                            .border(2.dp, borderColor, CircleShape)
                            .padding(3.dp)
                            .clip(CircleShape)
                            .graphicsLayer { alpha = if (day.isFuture) 0.45f else 1f },
                        contentAlignment = Alignment.Center,
                    ) {
                        MiniSunrise(progress = day.progress, date = day.date, modifier = Modifier.fillMaxSize())
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        day.date.dayOfMonth.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    )
                }
            }
        }
    }
}

@Composable
private fun PinnedHeader(
    visible: Boolean,
    state: HomeUiState,
    backdrop: Backdrop,
    statusPadding: Dp,
    onOpenHistory: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + slideInVertically { -it / 2 },
        exit = fadeOut() + slideOutVertically { -it / 2 },
        modifier = modifier,
    ) {
        val label = when (state.selectedDate) {
            state.today -> "Today"
            state.today.minusDays(1) -> "Yesterday"
            state.today.plusDays(1) -> "Tomorrow"
            else -> state.selectedDate.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
        }
        Row(
            Modifier
                .fillMaxWidth()
                .frosted(backdrop, glassTint(0.7f))
                .drawBehind { drawRect(Color.Black.copy(alpha = 0.06f), topLeft = Offset(0f, size.height - 1f), size = Size(size.width, 1f)) }
                .padding(top = statusPadding)
                .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(34.dp).clip(CircleShape)) { MiniSunrise(progress = state.progress, date = state.selectedDate, modifier = Modifier.fillMaxSize()) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    if (state.total == 0) "No habits yet" else "${state.done} of ${state.total} done · ${(state.progress * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onOpenHistory) { Icon(Icons.Rounded.CalendarMonth, contentDescription = "Sunrise history", tint = MaterialTheme.colorScheme.onSurface) }
            IconButton(onClick = onOpenSettings) { Icon(Icons.Rounded.Settings, contentDescription = "Settings", tint = MaterialTheme.colorScheme.onSurface) }
        }
    }
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
    val doneMix by animateFloatAsState(if (row.completed) 1f else 0f, spring(stiffness = Spring.StiffnessLow), label = "rowbg")
    val surface = MaterialTheme.colorScheme.surfaceContainer
    val dark = LocalIsDark.current
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

    val rowShape = RoundedCornerShape(22.dp)
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(rowShape)
            .drawBehind {
                drawRect(surface)
                if (doneMix > 0.01f) {
                    drawRect(
                        Brush.horizontalGradient(
                            0f to color.copy(alpha = 0.22f * doneMix),
                            0.6f to color.copy(alpha = 0.08f * doneMix),
                            1f to color.copy(alpha = 0.03f * doneMix),
                        ),
                    )
                }
                drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = if (dark) 0.035f else 0.5f), 1f to Color.Transparent, endY = size.height * 0.5f))
            }
            .glassBorder(rowShape, alpha = if (dark) 0.05f else 0.6f)
            .clickable(onClick = onOpen),
        shape = rowShape,
        color = Color.Transparent,
    ) {
        Row(Modifier.padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(HabitIcons[habit.icon], color, filled = row.completed)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    habit.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = if (row.completed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val subtitle = when (habit.type) {
                        HabitType.CHECK -> if (habit.scheduleType == ScheduleType.DAILY) habit.scheduleLabel() else "${row.periodDone}/${row.periodGoal} this ${if (habit.scheduleType == ScheduleType.WEEKLY) "week" else "month"}"
                        HabitType.COUNT -> "${formatValue(row.value)} / ${formatValue(habit.goal)} ${habit.unit}".trim()
                        HabitType.TIMER -> if (row.timer != null && row.timer.running) {
                            "● " + formatClock(elapsed) + " · goal ${formatDuration(habit.goal)}"
                        } else if (row.timer != null) {
                            "Paused " + formatClock(elapsed) + " · goal ${formatDuration(habit.goal)}"
                        } else "${formatDuration(row.value)} / ${formatDuration(habit.goal)}"
                    }
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = if (row.timer?.running == true) color else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (row.streak > 0) {
                        Spacer(Modifier.width(8.dp))
                        Icon(Icons.Rounded.LocalFireDepartment, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("${row.streak}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                    }
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
                modifier = Modifier.graphicsLayer { alpha = if (editable) 1f else 0.4f },
            )
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
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
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
                            HabitType.COUNT -> "Goal ${formatValue(habit.goal)} ${habit.unit}".trim()
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
