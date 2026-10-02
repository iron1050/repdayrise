package com.repdayrise.app.ui.partners

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.PersonRemove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repdayrise.app.data.model.HabitType
import com.repdayrise.app.data.model.formatDuration
import com.repdayrise.app.data.model.formatValue
import com.repdayrise.app.ui.components.ContributionGraph
import com.repdayrise.app.ui.components.GlassTopBar
import com.repdayrise.app.ui.components.GlowCard
import com.repdayrise.app.ui.components.HabitIcons
import com.repdayrise.app.ui.components.IconBadge
import com.repdayrise.app.ui.components.MiniSunrise
import com.repdayrise.app.ui.components.ProgressRing
import com.repdayrise.app.ui.components.SectionTitle
import com.repdayrise.app.ui.components.StatTile
import com.repdayrise.app.ui.components.SystemBars
import com.repdayrise.app.ui.components.backdropSource
import com.repdayrise.app.ui.components.dayriseBackground
import com.repdayrise.app.ui.components.rememberBackdrop
import com.repdayrise.app.ui.theme.HabitColors
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/** A read-only look at a partner's day and year. */
@Composable
fun PartnerDetailScreen(viewModel: PartnerDetailViewModel, onBack: () -> Unit) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val backdrop = rememberBackdrop()
    val snackbar = remember { SnackbarHostState() }
    var confirmUnfollow by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf<Long?>(null) }
    val dayFormat = remember { DateTimeFormatter.ofPattern("EEE, MMM d") }

    LaunchedEffect(state.loaded, state.found) { if (state.loaded && !state.found) onBack() }
    LaunchedEffect(state.message) {
        state.message?.let { snackbar.showSnackbar(it); viewModel.messageShown() }
    }
    SystemBars()

    Scaffold(
        modifier = Modifier.dayriseBackground(),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            GlassTopBar(
                backdrop = backdrop,
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
                actions = {
                    if (!state.ended) RefreshAction(state.busy) { viewModel.refresh() }
                    IconButton(onClick = { confirmUnfollow = true }) { Icon(Icons.Rounded.PersonRemove, contentDescription = "Stop following") }
                },
            )
        },
    ) { padding ->
        if (!state.found) {
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
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                Box(Modifier.size(64.dp).clip(CircleShape)) { MiniSunrise(progress = state.progress, date = state.today, modifier = Modifier.fillMaxSize()) }
                Spacer(Modifier.width(16.dp))
                Column {
                    Text(state.name, style = MaterialTheme.typography.headlineMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            !state.hasData -> "Waiting for their first sync"
                            state.total == 0 -> "Nothing scheduled today"
                            state.done == state.total -> "The sun is up. All ${state.total} done."
                            else -> "${state.done} of ${state.total} done today"
                        },
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        if (state.ended) "No longer shared with you" else "Updated ${relativeTime(state.updatedAt)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (state.ended) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile("${(state.progress * 100).roundToInt()}%", "Today", Modifier.weight(1f).fillMaxHeight())
                StatTile("${(state.weekAverage * 100).roundToInt()}%", "This week", Modifier.weight(1f).fillMaxHeight())
                StatTile("${state.perfectDays}", "Perfect days", Modifier.weight(1f).fillMaxHeight())
            }
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
                        summary = "${state.perfectDays} perfect ${if (state.perfectDays == 1) "day" else "days"}",
                        describe = { date ->
                            val pct = ((state.heatmap[date.toEpochDay()] ?: 0f) * 100).roundToInt()
                            date.format(dayFormat) + " · " + if (pct == 0) "Nothing logged" else "$pct% of the day's habits"
                        },
                    )
                }
            }

            if (state.rows.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                SectionTitle("Habits")
                Spacer(Modifier.height(10.dp))
            }
            state.rows.forEach { row ->
                val habit = row.habit
                val color = HabitColors.of(habit.colorIndex)
                val open = expanded == habit.id
                GlowCard(modifier = Modifier.padding(bottom = 10.dp), glow = if (row.completed || open) color else null) {
                    Column {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { expanded = if (open) null else habit.id }
                                .graphicsLayer { alpha = if (row.due || row.completed) 1f else 0.55f }
                                .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconBadge(HabitIcons[habit.icon], color, filled = row.completed)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(habit.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(2.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        if (row.due || row.completed) row.subtitle else "Not scheduled today",
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    )
                                    if (row.streak > 0) {
                                        Spacer(Modifier.width(8.dp))
                                        Icon(Icons.Rounded.LocalFireDepartment, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(2.dp))
                                        Text("${row.streak}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                ProgressRing(progress = if (row.completed) 1f else row.fraction, color = color, modifier = Modifier.fillMaxSize().padding(2.dp))
                                if (row.completed) Icon(Icons.Rounded.Check, contentDescription = "Completed", tint = color, modifier = Modifier.size(20.dp))
                            }
                        }
                        AnimatedVisibility(visible = open) {
                            ContributionGraph(
                                values = row.heatmap,
                                today = state.today,
                                weekStart = state.weekStart,
                                color = color,
                                firstDay = habit.startDate,
                                summary = "${row.yearDone} ${if (row.yearDone == 1) "day" else "days"} completed",
                                describe = { date ->
                                    val v = row.values[date.toEpochDay()] ?: 0.0
                                    val what = when {
                                        habit.type == HabitType.CHECK -> if (v > 0.0) "Completed" else "Not completed"
                                        habit.type == HabitType.TIMER -> "${formatDuration(v)} of ${formatDuration(habit.goal)}"
                                        else -> "${formatValue(v)} of ${formatValue(habit.goal)} ${habit.unit}".trim()
                                    }
                                    date.format(dayFormat) + " · " + what
                                },
                                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    if (confirmUnfollow) {
        ConfirmDialog(
            "Stop following ${state.name}?", "You'll need a new code from them to follow again.",
            "Stop following", { confirmUnfollow = false },
        ) { confirmUnfollow = false; viewModel.unfollow() }
    }
}
