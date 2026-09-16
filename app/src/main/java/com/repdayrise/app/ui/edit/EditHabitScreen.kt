package com.repdayrise.app.ui.edit

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.repdayrise.app.data.model.DaysMask
import com.repdayrise.app.data.model.HabitType
import com.repdayrise.app.data.model.HealthSource
import com.repdayrise.app.data.model.ScheduleType
import com.repdayrise.app.ui.components.HabitIcons
import com.repdayrise.app.ui.components.GlassTopBar
import com.repdayrise.app.ui.components.GlowCard
import com.repdayrise.app.ui.components.backdropSource
import com.repdayrise.app.ui.components.dayriseBackground
import com.repdayrise.app.ui.components.rememberBackdrop
import com.repdayrise.app.ui.components.IconBadge
import com.repdayrise.app.ui.components.SystemBars
import com.repdayrise.app.ui.theme.HabitColors
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EditHabitScreen(viewModel: EditHabitViewModel, onDone: () -> Unit) {
    val groups by viewModel.groups.collectAsStateWithLifecycle()
    val color = HabitColors.of(viewModel.colorIndex)
    var showTimePicker by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var newGroupDialog by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(viewModel.saved, viewModel.deleted) { if (viewModel.saved || viewModel.deleted) onDone() }
    SystemBars()

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val healthLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { }

    val backdrop = rememberBackdrop()
    Scaffold(
        modifier = Modifier.dayriseBackground(accent = color),
        containerColor = Color.Transparent,
        topBar = {
            GlassTopBar(
                backdrop = backdrop,
                title = { Text(if (viewModel.isNew) "New habit" else "Edit habit") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Rounded.Close, contentDescription = "Close") } },
                actions = {
                    Button(
                        onClick = { viewModel.save() },
                        enabled = viewModel.canSave && viewModel.loaded,
                        modifier = Modifier.padding(end = 12.dp),
                        shape = CircleShape,
                    ) { Text("Save") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .backdropSource(backdrop)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 16.dp)
                .padding(top = padding.calculateTopPadding() + 4.dp, bottom = 40.dp + padding.calculateBottomPadding()),
        ) {
            // Preview + name
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(HabitIcons[viewModel.icon], color, size = 60.dp, iconSize = 30.dp, filled = true)
                Spacer(Modifier.width(14.dp))
                OutlinedTextField(
                    value = viewModel.name,
                    onValueChange = { viewModel.name = it },
                    label = { Text("Habit name") },
                    placeholder = { Text("Morning walk") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = MaterialTheme.shapes.medium,
                )
            }
            Spacer(Modifier.height(20.dp))

            // Colours
            Label("Colour")
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp)) {
                items(HabitColors.palette.size) { i ->
                    val c = HabitColors.of(i)
                    val selected = i == viewModel.colorIndex
                    val scale by animateFloatAsState(if (selected) 1.15f else 1f, label = "c")
                    Box(
                        Modifier
                            .size(38.dp)
                            .scale(scale)
                            .clip(CircleShape)
                            .background(c)
                            .clickable { viewModel.colorIndex = i },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = Color.White)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))

            // Icons
            Label("Icon")
            GlowCard {
                LazyHorizontalGrid(
                    rows = GridCells.Fixed(3),
                    modifier = Modifier.fillMaxWidth().height(52.dp * 3 + 16.dp),
                    contentPadding = PaddingValues(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(HabitIcons.all, key = { it.first }) { (key, vector) ->
                        val selected = key == viewModel.icon
                        val bg by animateColorAsState(if (selected) color else Color.Transparent, label = "i")
                        Box(
                            Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(bg)
                                .clickable { viewModel.icon = key },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(vector, contentDescription = key, tint = if (selected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))

            // Type
            Label("Type")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val types = listOf(HabitType.CHECK to "Check", HabitType.COUNT to "Count", HabitType.TIMER to "Timer")
                types.forEachIndexed { i, (t, label) ->
                    SegmentedButton(
                        selected = viewModel.type == t,
                        onClick = { viewModel.changeType(t) },
                        shape = SegmentedButtonDefaults.itemShape(i, types.size),
                        icon = {},
                    ) { Text(label) }
                }
            }
            Spacer(Modifier.height(12.dp))
            AnimatedVisibility(visible = viewModel.type != HabitType.CHECK) {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = viewModel.goalText,
                            onValueChange = { viewModel.goalText = it.filter { c -> c.isDigit() || c == '.' } },
                            label = { Text(if (viewModel.type == HabitType.TIMER) "Goal (minutes)" else "Daily goal") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f),
                            shape = MaterialTheme.shapes.medium,
                        )
                        if (viewModel.type == HabitType.COUNT) {
                            OutlinedTextField(
                                value = viewModel.unit,
                                onValueChange = { viewModel.unit = it },
                                label = { Text("Unit") },
                                placeholder = { Text("glasses") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                shape = MaterialTheme.shapes.medium,
                            )
                        }
                    }
                    if (viewModel.type == HabitType.COUNT && viewModel.healthAvailable) {
                        Spacer(Modifier.height(14.dp))
                        Label("Auto-track with Health Connect")
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            HealthSource.entries.forEach { src ->
                                FilterChip(
                                    selected = viewModel.healthSource == src,
                                    onClick = {
                                        viewModel.setHealth(src)
                                        val perm = viewModel.healthPermission(src)
                                        if (perm != null) healthLauncher.launch(setOf(perm))
                                    },
                                    label = { Text(src.label) },
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = color.copy(alpha = 0.22f)),
                                )
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))

            // Schedule
            Label("Schedule")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                val options = listOf(ScheduleType.DAILY to "Daily", ScheduleType.WEEKLY to "Weekly", ScheduleType.MONTHLY to "Monthly")
                options.forEachIndexed { i, (s, label) ->
                    SegmentedButton(selected = viewModel.scheduleType == s, onClick = { viewModel.scheduleType = s }, shape = SegmentedButtonDefaults.itemShape(i, options.size), icon = {}) { Text(label) }
                }
            }
            Spacer(Modifier.height(12.dp))
            when (viewModel.scheduleType) {
                ScheduleType.DAILY -> {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        DayOfWeek.entries.forEach { day ->
                            val on = DaysMask.contains(viewModel.daysMask, day)
                            val bg by animateColorAsState(if (on) color else MaterialTheme.colorScheme.surfaceContainerHigh, label = "d")
                            Box(
                                Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(bg)
                                    .clickable { viewModel.toggleDay(day) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    day.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = if (on) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { viewModel.daysMask = DaysMask.ALL }, label = { Text("Every day") })
                        AssistChip(onClick = { viewModel.daysMask = DaysMask.WEEKDAYS }, label = { Text("Weekdays") })
                        AssistChip(onClick = { viewModel.daysMask = DaysMask.WEEKEND }, label = { Text("Weekends") })
                    }
                }
                else -> {
                    GlowCard {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("${viewModel.timesPerPeriod} ${if (viewModel.timesPerPeriod == 1) "time" else "times"} per ${if (viewModel.scheduleType == ScheduleType.WEEKLY) "week" else "month"}", style = MaterialTheme.typography.titleMedium)
                                Text("Complete on any days you like", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            FilledIconButton(onClick = { if (viewModel.timesPerPeriod > 1) viewModel.timesPerPeriod-- }, colors = IconButtonDefaults.filledTonalIconButtonColors()) { Icon(Icons.Rounded.Remove, contentDescription = "Fewer") }
                            Spacer(Modifier.width(8.dp))
                            FilledIconButton(onClick = { val max = if (viewModel.scheduleType == ScheduleType.WEEKLY) 7 else 31; if (viewModel.timesPerPeriod < max) viewModel.timesPerPeriod++ }, colors = IconButtonDefaults.filledIconButtonColors(containerColor = color)) { Icon(Icons.Rounded.Add, contentDescription = "More") }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))

            // Group
            Label("Group")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = viewModel.groupId == null, onClick = { viewModel.groupId = null }, label = { Text("None") })
                groups.forEach { g ->
                    FilterChip(
                        selected = viewModel.groupId == g.id,
                        onClick = { viewModel.groupId = g.id },
                        label = { Text(g.name) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = color.copy(alpha = 0.22f)),
                    )
                }
                AssistChip(onClick = { newGroupDialog = true }, label = { Text("New group") }, leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null, Modifier.size(18.dp)) })
            }
            Spacer(Modifier.height(20.dp))

            // Reminders
            Label("Reminders")
            GlowCard {
                Column(Modifier.padding(vertical = 4.dp)) {
                    viewModel.reminders.forEachIndexed { index, r ->
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Notifications, contentDescription = null, tint = color)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(r.timeLabel(), style = MaterialTheme.typography.titleMedium)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    DayOfWeek.entries.forEach { day ->
                                        val on = DaysMask.contains(r.daysMask, day)
                                        Text(
                                            day.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                                            color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                            modifier = Modifier.clickable {
                                                val next = DaysMask.toggle(r.daysMask, day)
                                                if (DaysMask.count(next) > 0) viewModel.updateReminder(index, r.copy(daysMask = next))
                                            }.padding(horizontal = 3.dp),
                                        )
                                    }
                                }
                            }
                            IconButton(onClick = { viewModel.removeReminder(index) }) { Icon(Icons.Rounded.Delete, contentDescription = "Remove reminder", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        }
                    }
                    TextButton(onClick = { notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS); showTimePicker = true }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text(if (viewModel.reminders.isEmpty()) "Add reminder" else "Add another")
                    }
                }
            }
            Spacer(Modifier.height(20.dp))

            // Start date + note
            Label("Details")
            GlowCard {
                Column {
                    Row(
                        Modifier.fillMaxWidth().clickable { showDatePicker = true }.padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Event, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Start date", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(viewModel.startDate.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy")), style = MaterialTheme.typography.titleMedium)
                        }
                    }
                    OutlinedTextField(
                        value = viewModel.note,
                        onValueChange = { viewModel.note = it },
                        label = { Text("Why this matters (optional)") },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
                        shape = MaterialTheme.shapes.medium,
                        minLines = 2,
                    )
                }
            }
            if (!viewModel.isNew) {
                Spacer(Modifier.height(28.dp))
                TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(6.dp))
                    Text("Delete habit", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }

    if (showTimePicker) {
        val tp = rememberTimePickerState(initialHour = 9, initialMinute = 0, is24Hour = false)
        AlertDialog(
            onDismissRequest = { showTimePicker = false },
            title = { Text("Remind me at") },
            text = { TimePicker(state = tp) },
            confirmButton = { TextButton(onClick = { viewModel.addReminder(tp.hour, tp.minute); showTimePicker = false }) { Text("Add") } },
            dismissButton = { TextButton(onClick = { showTimePicker = false }) { Text("Cancel") } },
        )
    }

    if (showDatePicker) {
        val dp = rememberDatePickerState(initialSelectedDateMillis = viewModel.startDate.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    dp.selectedDateMillis?.let { viewModel.startDate = Instant.ofEpochMilli(it).atZone(ZoneId.of("UTC")).toLocalDate() }
                    showDatePicker = false
                }) { Text("Set") }
            },
            dismissButton = { TextButton(onClick = { showDatePicker = false }) { Text("Cancel") } },
        ) { DatePicker(state = dp) }
    }

    if (newGroupDialog) {
        var text by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { newGroupDialog = false },
            title = { Text("New group") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("Name") }, placeholder = { Text("Morning ritual") }, singleLine = true, shape = MaterialTheme.shapes.medium) },
            confirmButton = { TextButton(onClick = { if (text.isNotBlank()) viewModel.createGroup(text); newGroupDialog = false }, enabled = text.isNotBlank()) { Text("Create") } },
            dismissButton = { TextButton(onClick = { newGroupDialog = false }) { Text("Cancel") } },
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this habit?") },
            text = { Text("Its whole history goes with it. This can't be undone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; viewModel.delete() }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
}
