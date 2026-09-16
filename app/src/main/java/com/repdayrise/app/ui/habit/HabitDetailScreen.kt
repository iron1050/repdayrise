package com.repdayrise.app.ui.habit

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repdayrise.app.data.model.Habit
import com.repdayrise.app.data.model.HabitType
import com.repdayrise.app.data.model.formatDuration
import com.repdayrise.app.data.model.formatValue
import com.repdayrise.app.domain.HabitStats
import com.repdayrise.app.ui.components.BarChart
import com.repdayrise.app.ui.components.GlassTopBar
import com.repdayrise.app.ui.components.GlowCard
import com.repdayrise.app.ui.components.backdropSource
import com.repdayrise.app.ui.components.dayriseBackground
import com.repdayrise.app.ui.components.rememberBackdrop
import com.repdayrise.app.ui.components.Gauge
import com.repdayrise.app.ui.components.HabitIcons
import com.repdayrise.app.ui.components.IconBadge
import com.repdayrise.app.ui.components.SystemBars
import com.repdayrise.app.ui.components.MonthCalendar
import com.repdayrise.app.ui.components.SectionTitle
import com.repdayrise.app.ui.components.StatTile
import com.repdayrise.app.ui.components.drawSky
import com.repdayrise.app.ui.share.ShareUtil
import com.repdayrise.app.ui.theme.HabitColors
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HabitDetailScreen(
    viewModel: HabitDetailViewModel,
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var valueDialogDate by remember { mutableStateOf<LocalDate?>(null) }
    var shareOpen by remember { mutableStateOf(false) }

    LaunchedEffect(state.deleted) { if (state.deleted) onBack() }
    SystemBars()

    val habit = state.habit
    val color = habit?.let { HabitColors.of(it.colorIndex) } ?: MaterialTheme.colorScheme.primary
    val backdrop = rememberBackdrop()

    Scaffold(
        modifier = Modifier.dayriseBackground(accent = color),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        topBar = {
            GlassTopBar(
                backdrop = backdrop,
                title = {},
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (habit != null) {
                        IconButton(onClick = { shareOpen = true }) { Icon(Icons.Rounded.IosShare, contentDescription = "Share stats") }
                        IconButton(onClick = { onEdit(habit.id) }) { Icon(Icons.Rounded.Edit, contentDescription = "Edit") }
                        Box {
                            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(if (habit.archived) "Restore from Hall of Fame" else "Move to Hall of Fame") },
                                    leadingIcon = { Icon(if (habit.archived) Icons.Rounded.Unarchive else Icons.Rounded.Archive, null) },
                                    onClick = { menuOpen = false; viewModel.setArchived(!habit.archived) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete habit", color = MaterialTheme.colorScheme.error) },
                                    leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = MaterialTheme.colorScheme.error) },
                                    onClick = { menuOpen = false; confirmDelete = true },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            AnimatedVisibility(visible = state.selection.isNotEmpty(), enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp) {
                    Row(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("${state.selection.size} selected", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = { viewModel.clearSelection() }) { Text("Cancel") }
                        FilledTonalButton(onClick = { viewModel.applySelection(0.0) }) { Text("Clear") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = { viewModel.applySelection(null) }, colors = ButtonDefaults.buttonColors(containerColor = color)) { Text("Mark done") }
                    }
                }
            }
        },
    ) { padding ->
        if (habit == null) {
            Box(Modifier.fillMaxSize().padding(padding))
            return@Scaffold
        }
        Column(
            Modifier
                .fillMaxSize()
                .backdropSource(backdrop)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = padding.calculateTopPadding() + 4.dp, bottom = 32.dp + padding.calculateBottomPadding()),
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                IconBadge(HabitIcons[habit.icon], color, size = 64.dp, iconSize = 32.dp, filled = true)
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(habit.name, style = MaterialTheme.typography.headlineMedium)
                    Text(
                        listOf(habit.scheduleLabel(), if (habit.isMeasurable) habit.goalLabel() else null).filterNotNull().joinToString(" · "),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (habit.archived) Text("In the Hall of Fame", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("${state.stats.currentStreak}", "Current streak", Modifier.weight(1f).fillMaxHeight(), accent = color)
                StatTile("${state.stats.bestStreak}", "Best streak", Modifier.weight(1f).fillMaxHeight(), accent = color)
                StatTile("${state.stats.totalDays}", "Total days", Modifier.weight(1f).fillMaxHeight(), accent = color)
            }
            Spacer(Modifier.height(20.dp))

            // Calendar
            GlowCard(glow = color) {
                Column(Modifier.padding(14.dp)) {
                    MonthCalendar(
                        month = state.month,
                        weekStart = state.weekStart,
                        onPrevious = { viewModel.previousMonth() },
                        onNext = { viewModel.nextMonth() },
                        canGoNext = state.month.isBefore(java.time.YearMonth.now()),
                    ) { date ->
                        if (date == null) { Box(Modifier.size(38.dp)); return@MonthCalendar }
                        val status = state.monthStatuses[date]
                        val selected = date in state.selection
                        val future = date.isAfter(state.today)
                        val beforeStart = date.isBefore(habit.startDate)
                        val fraction = status?.fraction ?: 0f
                        val ringAlpha by animateFloatAsState(if (status?.completed == true) 1f else 0f, label = "day")
                        Box(
                            Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, CircleShape) else Modifier)
                                .then(if (date == state.today) Modifier.border(1.5.dp, color.copy(alpha = 0.7f), CircleShape) else Modifier)
                                .background(
                                    when {
                                        status?.completed == true -> color.copy(alpha = ringAlpha)
                                        fraction > 0f -> color.copy(alpha = 0.18f + 0.35f * fraction)
                                        status?.scheduled == true && !future && !beforeStart -> MaterialTheme.colorScheme.surfaceContainerHighest
                                        else -> Color.Transparent
                                    },
                                )
                                .combinedClickable(
                                    enabled = !future && !beforeStart,
                                    onClick = {
                                        if (state.selection.isNotEmpty()) viewModel.toggleSelection(date)
                                        else if (habit.type == HabitType.CHECK) { haptic.performHapticFeedback(HapticFeedbackType.Confirm); viewModel.toggleDay(date) }
                                        else valueDialogDate = date
                                    },
                                    onLongClick = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); viewModel.toggleSelection(date) },
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                date.dayOfMonth.toString(),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = if (status?.completed == true) FontWeight.Bold else FontWeight.Medium,
                                color = when {
                                    status?.completed == true -> Color.White
                                    future || beforeStart -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                                    else -> MaterialTheme.colorScheme.onSurface
                                },
                            )
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Tap a day to edit · long-press to select several",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            // Gauge
            GlowCard(glow = color) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Gauge(
                        fraction = state.monthCompletion,
                        color = color,
                        modifier = Modifier.size(132.dp),
                        label = "${(state.monthCompletion * 100).roundToInt()}%",
                        sublabel = "this month",
                    )
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(state.month.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text("${state.monthDone} ${if (state.monthDone == 1) "day" else "days"} completed", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            when {
                                state.monthCompletion >= 1f -> "Perfect month. The sun never set."
                                state.monthCompletion >= 0.75f -> "Strong and steady."
                                state.monthCompletion >= 0.4f -> "Building momentum."
                                else -> "Every day is a fresh sunrise."
                            },
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            // Statistics
            GlowCard {
                Column(Modifier.padding(16.dp)) {
                    SectionTitle("Statistics")
                    Spacer(Modifier.height(12.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        StatPeriod.entries.forEachIndexed { i, p ->
                            SegmentedButton(
                                selected = state.period == p,
                                onClick = { viewModel.setPeriod(p) },
                                shape = SegmentedButtonDefaults.itemShape(i, StatPeriod.entries.size),
                                colors = SegmentedButtonDefaults.colors(activeContainerColor = color.copy(alpha = 0.2f), activeContentColor = MaterialTheme.colorScheme.onSurface),
                                icon = {},
                            ) { Text(p.label) }
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    BarChart(
                        values = state.chart.values,
                        labels = state.chart.labels,
                        color = color,
                        highlight = state.chart.highlight,
                        modifier = Modifier.fillMaxWidth().height(150.dp),
                    )
                    Spacer(Modifier.height(14.dp))
                    Row {
                        Column(Modifier.weight(1f)) {
                            Text("Total", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${state.chart.total} ${state.chart.unit}".trim(), style = MaterialTheme.typography.titleMedium)
                        }
                        Column(Modifier.weight(1f)) {
                            Text("Average", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(state.chart.average, style = MaterialTheme.typography.titleMedium)
                        }
                        Column(Modifier.weight(1.2f)) {
                            Text("Compared", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                state.chart.comparison,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (state.chart.comparisonPositive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
            if (habit.note.isNotBlank()) {
                Spacer(Modifier.height(16.dp))
                GlowCard {
                    Column(Modifier.padding(16.dp)) {
                        Text("Why this matters", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.height(4.dp))
                        Text(habit.note, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete ${habit?.name ?: "habit"}?") },
            text = { Text("All history for this habit will be removed. Prefer keeping the record? Move it to the Hall of Fame instead.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }

    val dialogDate = valueDialogDate
    if (dialogDate != null && habit != null) {
        val current = state.monthStatuses[dialogDate]?.value ?: 0.0
        var text by remember(dialogDate) { mutableStateOf(if (current == 0.0) "" else formatValue(current)) }
        AlertDialog(
            onDismissRequest = { valueDialogDate = null },
            title = { Text(dialogDate.format(DateTimeFormatter.ofPattern("EEEE, MMM d"))) },
            text = {
                Column {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it.filter { c -> c.isDigit() || c == '.' } },
                        label = { Text(if (habit.type == HabitType.TIMER) "Minutes" else habit.unit.ifBlank { "Value" }) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        shape = MaterialTheme.shapes.medium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("Goal: ${habit.goalLabel()}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.setValue(dialogDate, text.toDoubleOrNull() ?: 0.0); valueDialogDate = null }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.setValue(dialogDate, habit.effectiveGoal); valueDialogDate = null }) { Text("Complete") }
                    TextButton(onClick = { valueDialogDate = null }) { Text("Cancel") }
                }
            },
        )
    }

    if (shareOpen && habit != null) {
        ShareDialog(habit = habit, stats = state.stats, monthCompletion = state.monthCompletion, onDismiss = { shareOpen = false })
    }
}

@Composable
private fun ShareDialog(habit: Habit, stats: HabitStats, monthCompletion: Float, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    val color = HabitColors.of(habit.colorIndex)
    Dialog(onDismissRequest = onDismiss) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(28.dp))
                    .drawWithContent {
                        layer.record { this@drawWithContent.drawContent() }
                        drawLayer(layer)
                    },
            ) {
                androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                    drawSky(progress = monthCompletion.coerceAtLeast(0.35f), moonPhase = 0.5, hillFraction = 0.2f, detail = false)
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.35f)))))
                Column(Modifier.fillMaxSize().padding(24.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(HabitIcons[habit.icon], color, size = 44.dp, filled = true)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(habit.name, style = MaterialTheme.typography.titleLarge, color = Color.White)
                            Text(habit.scheduleLabel(), style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.85f))
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    Text("${stats.currentStreak}", style = MaterialTheme.typography.displayLarge, color = Color.White)
                    Text("${stats.streakUnit} streak", style = MaterialTheme.typography.titleMedium, color = Color.White.copy(alpha = 0.9f))
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("${stats.bestStreak}", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                            Text("best", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
                        }
                        Column(Modifier.weight(1f)) {
                            Text("${stats.totalDays}", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                            Text("total days", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
                        }
                        Column(Modifier.weight(1f)) {
                            Text("${(monthCompletion * 100).roundToInt()}%", style = MaterialTheme.typography.headlineSmall, color = Color.White)
                            Text("this month", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.8f))
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Text("Dayrise", style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.End, modifier = Modifier.fillMaxWidth())
                }
            }
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FilledTonalButton(onClick = onDismiss) { Text("Close") }
                Button(onClick = {
                    scope.launch {
                        val bitmap = layer.toImageBitmap().asAndroidBitmap()
                        ShareUtil.shareBitmap(context, bitmap, "${habit.name} · Dayrise")
                        onDismiss()
                    }
                }) {
                    Icon(Icons.Rounded.IosShare, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Share image")
                }
            }
        }
    }
}
