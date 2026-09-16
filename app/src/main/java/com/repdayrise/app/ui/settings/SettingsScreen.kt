package com.repdayrise.app.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.EmojiEvents
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Restore
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.health.connect.client.PermissionController
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.repdayrise.app.AppContainer
import com.repdayrise.app.data.Settings as AppSettings
import com.repdayrise.app.data.model.AppIcon
import com.repdayrise.app.data.model.Habit
import com.repdayrise.app.data.model.ThemeMode
import com.repdayrise.app.domain.HabitLogic
import com.repdayrise.app.ui.components.HabitIcons
import com.repdayrise.app.ui.components.GlassTopBar
import com.repdayrise.app.ui.components.GlowCard
import com.repdayrise.app.ui.components.backdropSource
import com.repdayrise.app.ui.components.dayriseBackground
import com.repdayrise.app.ui.components.rememberBackdrop
import com.repdayrise.app.ui.components.IconBadge
import com.repdayrise.app.ui.components.SystemBars
import com.repdayrise.app.ui.theme.HabitColors
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate

class SettingsViewModel(private val container: AppContainer) : ViewModel() {
    val settings: StateFlow<AppSettings> = container.settings.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings())

    val healthAvailable: Boolean get() = container.health.isAvailable
    val allHealthPermissions: Set<String> get() = container.health.allPermissions
    suspend fun healthGranted(): Int = container.health.grantedPermissions().size
    fun canScheduleExact(): Boolean = container.reminderScheduler.canScheduleExact()

    fun setTheme(mode: ThemeMode) = viewModelScope.launch { container.settings.setThemeMode(mode) }
    fun setDynamic(enabled: Boolean) = viewModelScope.launch { container.settings.setDynamicColor(enabled) }
    fun setWeekStartsMonday(monday: Boolean) = viewModelScope.launch { container.settings.setWeekStartsMonday(monday) }
    fun setCompletedToBottom(enabled: Boolean) = viewModelScope.launch { container.settings.setCompletedToBottom(enabled) }
    fun setHaptics(enabled: Boolean) = viewModelScope.launch { container.settings.setHaptics(enabled) }
    fun setAppIcon(icon: AppIcon) = viewModelScope.launch {
        container.settings.setAppIcon(icon)
        container.applyAppIcon(icon)
    }
}

data class HallEntry(val habit: Habit, val bestStreak: Int, val totalDays: Int, val unit: String)

class HallOfFameViewModel(container: AppContainer) : ViewModel() {
    private val repo = container.repository
    val entries: StateFlow<List<HallEntry>> = combine(repo.archivedHabits, repo.entries, container.settings.settings) { habits, entries, settings ->
        val logic = HabitLogic(if (settings.weekStartsMonday) DayOfWeek.MONDAY else DayOfWeek.SUNDAY)
        habits.sortedByDescending { it.archivedAt ?: 0L }.map { h ->
            val s = logic.stats(h, entries, LocalDate.now())
            HallEntry(h, s.bestStreak, s.totalDays, s.streakUnit)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun restore(id: Long) = viewModelScope.launch { repo.setArchived(id, false) }
    fun delete(id: Long) = viewModelScope.launch { repo.deleteHabit(id) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel, onBack: () -> Unit, onOpenHallOfFame: () -> Unit) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var notifGranted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) }
    var exactAlarms by remember { mutableStateOf(viewModel.canScheduleExact()) }
    var healthCount by remember { mutableStateOf(-1) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { notifGranted = it }
    val healthLauncher = rememberLauncherForActivityResult(PermissionController.createRequestPermissionResultContract()) { healthCount = it.size }

    LifecycleResumeEffect(Unit) {
        notifGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        exactAlarms = viewModel.canScheduleExact()
        onPauseOrDispose { }
    }
    LaunchedEffect(Unit) { if (viewModel.healthAvailable) healthCount = viewModel.healthGranted() }
    SystemBars()
    val backdrop = rememberBackdrop()

    Scaffold(
        modifier = Modifier.dayriseBackground(),
        containerColor = Color.Transparent,
        topBar = {
            GlassTopBar(
                backdrop = backdrop,
                title = { Text("Settings") },
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
                .padding(top = padding.calculateTopPadding(), bottom = 40.dp + padding.calculateBottomPadding()),
        ) {
            Group("Appearance") {
                Column(Modifier.padding(16.dp)) {
                    Text("Theme", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.height(8.dp))
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        val modes = listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark")
                        modes.forEachIndexed { i, (m, label) ->
                            SegmentedButton(selected = settings.themeMode == m, onClick = { viewModel.setTheme(m) }, shape = SegmentedButtonDefaults.itemShape(i, modes.size), icon = {}) { Text(label) }
                        }
                    }
                }
                ToggleRow("Material You colours", "Tint the app with your wallpaper palette", settings.dynamicColor) { viewModel.setDynamic(it) }
            }

            Group("App icon") {
                Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    AppIcon.entries.forEach { icon ->
                        val selected = settings.appIcon == icon
                        Column(
                            Modifier.weight(1f).clip(MaterialTheme.shapes.medium).clickable { viewModel.setAppIcon(icon) }.padding(6.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            IconPreview(icon, selected)
                            Spacer(Modifier.height(6.dp))
                            Text(icon.label, style = MaterialTheme.typography.labelMedium, color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            Group("Habits") {
                ToggleRow("Week starts on Monday", "Affects weekly goals, streaks and calendars", settings.weekStartsMonday) { viewModel.setWeekStartsMonday(it) }
                ToggleRow("Move completed to the bottom", "Keep what's left front and centre", settings.completedToBottom) { viewModel.setCompletedToBottom(it) }
                ToggleRow("Haptics", "A gentle tap when you complete a habit", settings.haptics) { viewModel.setHaptics(it) }
                NavRow("Hall of Fame", "Habits you've completed and retired", Icons.Rounded.EmojiEvents, onOpenHallOfFame)
            }

            Group("Reminders") {
                NavRow(
                    "Notifications",
                    if (notifGranted) "Allowed" else "Tap to allow reminders",
                    Icons.Rounded.Notifications,
                ) {
                    if (!notifGranted) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }
                NavRow(
                    "Exact timing",
                    if (exactAlarms) "Reminders fire on the minute" else "Allow exact alarms so reminders are punctual",
                    Icons.Rounded.Restore,
                ) {
                    if (!exactAlarms) context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                }
            }

            Group("Health Connect") {
                if (viewModel.healthAvailable) {
                    NavRow(
                        "Sync with Health Connect",
                        when {
                            healthCount < 0 -> "Checking…"
                            healthCount == 0 -> "Auto-track steps, water, sleep, exercise and more"
                            else -> "$healthCount data types connected"
                        },
                        Icons.Rounded.Favorite,
                    ) { healthLauncher.launch(viewModel.allHealthPermissions) }
                } else {
                    Text(
                        "Health Connect isn't available on this device.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp),
                    )
                }
            }

            Group("About") {
                Column(Modifier.padding(16.dp)) {
                    Text("Dayrise for Android", style = MaterialTheme.typography.titleSmall)
                    Text("Version ${runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "1.0"} · Android ${Build.VERSION.RELEASE}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Text("Complete your habits and watch the sun rise. Everything stays on your device.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun IconPreview(icon: AppIcon, selected: Boolean) {
    val brush = when (icon) {
        AppIcon.SUNRISE -> Brush.verticalGradient(listOf(Color(0xFF0B1026), Color(0xFF3A2F6B), Color(0xFFC2587A), Color(0xFFF79B5E)))
        AppIcon.NIGHT -> Brush.verticalGradient(listOf(Color(0xFF05060F), Color(0xFF1B2450)))
        AppIcon.MONO -> Brush.verticalGradient(listOf(Color(0xFFF7F4EE), Color(0xFFF7F4EE)))
    }
    Box(
        Modifier
            .size(64.dp)
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(20.dp))
            .padding(4.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(brush),
        contentAlignment = Alignment.Center,
    ) {
        when (icon) {
            AppIcon.SUNRISE -> Box(Modifier.size(24.dp).clip(RoundedCornerShape(50)).background(Brush.verticalGradient(listOf(Color(0xFFFFF1B8), Color(0xFFFF8A3D)))))
            AppIcon.NIGHT -> Box(Modifier.size(22.dp).clip(RoundedCornerShape(50)).background(Color(0xFFF5F0D8)))
            AppIcon.MONO -> Icon(HabitIcons["sunrise"], contentDescription = null, tint = Color(0xFF1B2350), modifier = Modifier.size(28.dp))
        }
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp))
    GlowCard {
        Column { content() }
    }
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun ToggleRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun NavRow(title: String, subtitle: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HallOfFameScreen(viewModel: HallOfFameViewModel, onBack: () -> Unit, onOpenHabit: (Long) -> Unit) {
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf<Habit?>(null) }
    val backdrop = rememberBackdrop()
    SystemBars()
    Scaffold(
        modifier = Modifier.dayriseBackground(),
        containerColor = Color.Transparent,
        topBar = {
            GlassTopBar(
                backdrop = backdrop,
                title = { Text("Hall of Fame") },
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
                .padding(top = padding.calculateTopPadding(), bottom = 32.dp + padding.calculateBottomPadding()),
        ) {
            if (entries.isEmpty()) {
                Column(Modifier.fillMaxWidth().padding(top = 60.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.EmojiEvents, contentDescription = null, tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(56.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Nothing retired yet", style = MaterialTheme.typography.titleLarge)
                    Text("When a habit becomes second nature, move it here to keep its record.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 24.dp, vertical = 6.dp))
                }
            }
            entries.forEach { e ->
                GlowCard(glow = HabitColors.of(e.habit.colorIndex), modifier = Modifier.padding(vertical = 5.dp).clickable { onOpenHabit(e.habit.id) }) {
                    Row(Modifier.padding(start = 14.dp, end = 4.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconBadge(HabitIcons[e.habit.icon], HabitColors.of(e.habit.colorIndex), filled = true)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(e.habit.name, style = MaterialTheme.typography.titleMedium)
                            Text("Best ${e.bestStreak} ${e.unit} · ${e.totalDays} total days", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { viewModel.restore(e.habit.id) }) { Icon(Icons.Rounded.Restore, contentDescription = "Restore") }
                        IconButton(onClick = { confirmDelete = e.habit }) { Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
    }
    confirmDelete?.let { h ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${h.name}?") },
            text = { Text("Its record will be gone for good.") },
            confirmButton = { TextButton(onClick = { viewModel.delete(h.id); confirmDelete = null }) { Text("Delete", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}
