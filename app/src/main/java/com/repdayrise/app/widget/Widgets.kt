package com.repdayrise.app.widget

import android.content.Context
import android.content.res.Configuration
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.repdayrise.app.MainActivity
import com.repdayrise.app.container
import com.repdayrise.app.data.model.DaysMask
import com.repdayrise.app.data.model.Habit
import com.repdayrise.app.data.model.HabitType
import com.repdayrise.app.data.model.ScheduleType
import com.repdayrise.app.data.model.ThemeMode
import com.repdayrise.app.data.model.formatDuration
import com.repdayrise.app.data.model.formatValue
import com.repdayrise.app.domain.HabitLogic
import com.repdayrise.app.ui.components.HabitIcons
import com.repdayrise.app.ui.theme.Gold
import com.repdayrise.app.ui.theme.HabitColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale
import kotlin.math.roundToInt

/* =========================================================================================
 * Shared plumbing
 * ======================================================================================= */

class WidgetUpdater(private val context: Context) {
    suspend fun updateAll() {
        runCatching { SunriseWidget().updateAll(context) }
        runCatching { DashboardWidget().updateAll(context) }
        runCatching { HabitsWidget().updateAll(context) }
        runCatching { RhythmWidget().updateAll(context) }
    }

    /** Publishes generated previews to the widget picker (Android 15+). Rate limited by the OS, so callers guard it. */
    suspend fun publishPreviews() {
        if (Build.VERSION.SDK_INT < 35) return
        val manager = GlanceAppWidgetManager(context)
        runCatching { manager.setWidgetPreviews(SunriseWidgetReceiver::class) }
        runCatching { manager.setWidgetPreviews(DashboardWidgetReceiver::class) }
        runCatching { manager.setWidgetPreviews(HabitsWidgetReceiver::class) }
        runCatching { manager.setWidgetPreviews(RhythmWidgetReceiver::class) }
    }
}

data class WidgetHabit(
    val habit: Habit,
    val done: Boolean,
    val fraction: Float,
    val value: Double,
    val streak: Int,
    /** Last 14 days, oldest first. null = not scheduled that day. */
    val trail: List<Float?>,
)

data class WidgetData(
    val today: LocalDate,
    val habits: List<WidgetHabit>,
    val done: Int,
    val total: Int,
    val progress: Float,
    val month: YearMonth,
    val monthDays: Map<Int, Float>,
    val monthAverage: Float,
    val perfectDays: Int,
    val perfectStreak: Int,
    val weekStart: DayOfWeek,
    val dark: Boolean,
)

private const val TRAIL_DAYS = 14

private fun systemNight(context: Context): Boolean =
    (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

private suspend fun loadWidgetData(context: Context): WidgetData {
    val container = context.container
    val settings = container.settings.current()
    val logic = container.logicFromSettings()
    val today = LocalDate.now()
    val habits = container.repository.getAllHabitsOnce().filter { !it.archived }
    val entries = container.repository.getEntriesOnce()
    val due = habits.filter { logic.isDue(it, entries, today) }
    val (done, total) = logic.dayCounts(habits, entries, today)
    val rows = due.map { h ->
        val v = logic.value(entries, h.id, today)
        val trail = (TRAIL_DAYS - 1 downTo 0).map { back ->
            val d = today.minusDays(back.toLong())
            if (d.isBefore(h.startDate) || (h.scheduleType == ScheduleType.DAILY && !DaysMask.contains(h.daysMask, d.dayOfWeek))) null
            else logic.fraction(h, logic.value(entries, h.id, d))
        }
        WidgetHabit(h, logic.isCompleted(h, v), logic.fraction(h, v), v, logic.stats(h, entries, today).currentStreak, trail)
    }
    val month = YearMonth.from(today)
    val monthDays = HashMap<Int, Float>()
    var perfect = 0
    var sum = 0f
    var counted = 0
    for (day in 1..month.lengthOfMonth()) {
        val d = month.atDay(day)
        if (d.isAfter(today)) break
        val (dd, tt) = logic.dayCounts(habits, entries, d)
        if (tt == 0) continue
        val p = logic.dayProgress(habits, entries, d)
        monthDays[day] = p
        sum += p; counted++
        if (dd == tt) perfect++
    }
    // Streak of consecutive perfect days ending today (today counts only if already perfect).
    var streak = 0
    var d = today
    var first = true
    while (true) {
        val (dd, tt) = logic.dayCounts(habits, entries, d)
        if (tt == 0) { if (d.isBefore(today.minusDays(400))) break; d = d.minusDays(1); continue }
        if (dd == tt) streak++ else if (!first) break
        first = false
        d = d.minusDays(1)
        if (d.isBefore(today.minusDays(400))) break
    }
    val dark = when (settings.themeMode) {
        ThemeMode.SYSTEM -> systemNight(context)
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    return WidgetData(
        today = today, habits = rows, done = done, total = total, progress = logic.dayProgress(habits, entries, today),
        month = month, monthDays = monthDays, monthAverage = if (counted == 0) 0f else sum / counted, perfectDays = perfect,
        perfectStreak = streak, weekStart = logic.weekDays(today).first().dayOfWeek, dark = dark,
    )
}

/** Sample data for widget-picker previews. */
private fun sampleData(context: Context): WidgetData {
    val today = LocalDate.now()
    val sample = listOf(
        Habit(id = 1, name = "Morning walk", icon = "walk", colorIndex = 4, type = HabitType.TIMER, goal = 20.0, unit = "min"),
        Habit(id = 2, name = "Drink water", icon = "water", colorIndex = 6, type = HabitType.COUNT, goal = 8.0, unit = "glasses"),
        Habit(id = 3, name = "Read", icon = "book", colorIndex = 8),
        Habit(id = 4, name = "Meditate", icon = "yoga", colorIndex = 9),
        Habit(id = 5, name = "Stretch", icon = "sun", colorIndex = 0),
    )
    val fractions = listOf(1f, 0.625f, 1f, 0f, 0f)
    val r = java.util.Random(7)
    val rows = sample.mapIndexed { i, h ->
        val f = fractions[i]
        WidgetHabit(h, f >= 1f, f, f * h.goal, if (f >= 1f) 6 + i else 0, List(TRAIL_DAYS) { if (r.nextFloat() < 0.75f) 1f else if (r.nextFloat() < 0.5f) 0.5f else 0f })
    }
    val month = YearMonth.from(today)
    val days = (1..today.dayOfMonth).associateWith { (0.35f + r.nextFloat() * 0.65f).coerceAtMost(1f) }
    return WidgetData(
        today = today, habits = rows, done = 2, total = 5, progress = 0.52f, month = month, monthDays = days,
        monthAverage = 0.71f, perfectDays = 9, perfectStreak = 3, weekStart = DayOfWeek.MONDAY, dark = systemNight(context),
    )
}

val HabitIdKey = ActionParameters.Key<Long>(MainActivity.EXTRA_HABIT_ID)
val OpenHistoryKey = ActionParameters.Key<Boolean>(MainActivity.EXTRA_OPEN_HISTORY)

private fun openApp(): Action = actionStartActivity<MainActivity>()
private fun openHabit(id: Long): Action = actionStartActivity<MainActivity>(actionParametersOf(HabitIdKey to id))
private fun openHistory(): Action = actionStartActivity<MainActivity>(actionParametersOf(OpenHistoryKey to true))
private fun toggle(id: Long): Action = actionRunCallback<ToggleHabitAction>(actionParametersOf(HabitIdKey to id))

class ToggleHabitAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val id = parameters[HabitIdKey] ?: return
        val repo = context.container.repository
        val habit = repo.getHabit(id) ?: return
        val today = LocalDate.now()
        when (habit.type) {
            HabitType.CHECK -> repo.toggle(habit, today)
            HabitType.COUNT -> {
                val current = repo.currentValue(id, today)
                if (current + 1e-9 >= habit.effectiveGoal) repo.setValue(id, today, 0.0) else repo.increment(habit, today, 1.0)
            }
            HabitType.TIMER -> repo.toggle(habit, today)
        }
        // The repository listener refreshes every widget; nothing else to do here.
    }
}

private fun px(context: Context, dp: Float): Int = WidgetRender.dp(context, dp)
private fun style(color: Color, size: Int, weight: FontWeight = FontWeight.Medium) =
    TextStyle(color = ColorProvider(color), fontSize = size.sp, fontWeight = weight)

private fun WidgetHabit.subtitle(): String = when (habit.type) {
    HabitType.COUNT -> "${formatValue(value)} / ${formatValue(habit.goal)} ${habit.unit}".trim()
    HabitType.TIMER -> "${formatDuration(value)} / ${formatDuration(habit.goal)}"
    HabitType.CHECK -> if (streak > 0) "$streak day streak" else habit.scheduleLabel()
}

/* =========================================================================================
 * 1. Sunrise – the living sky with today's progress (2x2 and up)
 * ======================================================================================= */

class SunriseWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SunriseWidget()
}

class SunriseWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadWidgetData(context)
        provideContent { Content(data) }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { Content(sampleData(context)) }
    }

    @Composable
    private fun Content(data: WidgetData) {
        val size = LocalSize.current
        val context = LocalContext.current
        val palette = WidgetRender.Palette(data.dark)
        val radius = 24.dp
        val sky = WidgetRender.sky(px(context, size.width.value), px(context, size.height.value), data.progress, data.today, px(context, radius.value).toFloat())
        val wide = size.width >= 200.dp
        val tall = size.height >= 150.dp
        Box(modifier = GlanceModifier.fillMaxSize().cornerRadius(radius).clickable(openApp())) {
            Image(ImageProvider(sky), contentDescription = null, modifier = GlanceModifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Column(modifier = GlanceModifier.fillMaxSize().padding(14.dp)) {
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("Today", style = style(Color.White.copy(alpha = 0.85f), 12, FontWeight.Bold))
                    Spacer(GlanceModifier.defaultWeight())
                    Text(data.today.format(DateTimeFormatter.ofPattern("EEE d")), style = style(Color.White.copy(alpha = 0.75f), 11))
                }
                Spacer(GlanceModifier.defaultWeight())
                Text("${(data.progress * 100).roundToInt()}%", style = style(Color.White, if (tall) 34 else 28, FontWeight.Bold))
                Text(
                    when {
                        data.total == 0 -> "No habits yet"
                        data.done == data.total -> "All done · beautiful"
                        else -> "${data.done} of ${data.total} done"
                    },
                    style = style(Color.White.copy(alpha = 0.9f), 12),
                )
                if (wide && data.habits.isNotEmpty()) {
                    Spacer(GlanceModifier.height(10.dp))
                    val badge = 34
                    val cols = ((size.width.value - 28) / (badge + 8)).toInt().coerceIn(1, 10)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        data.habits.take(cols).forEach { h ->
                            val bmp = WidgetRender.badge(HabitIcons[h.habit.icon], HabitColors.of(h.habit.colorIndex), px(context, badge.toFloat()), h.done, h.fraction, palette)
                            Image(
                                ImageProvider(bmp), contentDescription = h.habit.name,
                                modifier = GlanceModifier.size(badge.dp).clickable(toggle(h.habit.id)),
                            )
                            Spacer(GlanceModifier.width(8.dp))
                        }
                    }
                }
            }
        }
    }
}

/* =========================================================================================
 * 2. Dashboard – a grid of habit icons that double as check-in buttons
 * ======================================================================================= */

class DashboardWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = DashboardWidget()
}

class DashboardWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadWidgetData(context)
        provideContent { Content(data) }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { Content(sampleData(context)) }
    }

    @Composable
    private fun Content(data: WidgetData) {
        val size = LocalSize.current
        val context = LocalContext.current
        val palette = WidgetRender.Palette(data.dark)
        val radius = 24.dp
        val bg = WidgetRender.card(px(context, size.width.value / 2), px(context, size.height.value / 2), palette, px(context, radius.value / 2).toFloat())
        val cell = 58f
        val cols = ((size.width.value - 20) / cell).toInt().coerceIn(2, 8)
        val rows = ((size.height.value - 46) / cell).toInt().coerceAtLeast(1)
        val capacity = cols * rows
        val shown = data.habits.take(capacity)
        val overflow = data.habits.size - shown.size

        Box(modifier = GlanceModifier.fillMaxSize().cornerRadius(radius)) {
            Image(ImageProvider(bg), contentDescription = null, modifier = GlanceModifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Column(modifier = GlanceModifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 10.dp)) {
                Row(
                    modifier = GlanceModifier.fillMaxWidth().padding(horizontal = 4.dp).clickable(openApp()),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(ImageProvider(WidgetRender.miniSun(px(context, 22f), data.progress, data.today)), contentDescription = null, modifier = GlanceModifier.size(22.dp))
                    Spacer(GlanceModifier.width(8.dp))
                    Text(
                        if (data.total == 0) "Dayrise" else "${data.done} of ${data.total}",
                        style = style(palette.text, 13, FontWeight.Bold),
                    )
                    Spacer(GlanceModifier.defaultWeight())
                    Text(data.today.format(DateTimeFormatter.ofPattern("EEE, MMM d")), style = style(palette.muted, 11))
                }
                Spacer(GlanceModifier.height(6.dp))
                if (shown.isEmpty()) {
                    Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (data.total == 0) "Add a habit to begin" else "Nothing due today", style = style(palette.muted, 12))
                    }
                } else {
                    Column(modifier = GlanceModifier.fillMaxWidth()) {
                        shown.chunked(cols).forEach { rowItems ->
                            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                rowItems.forEach { h ->
                                    Box(GlanceModifier.defaultWeight().height(cell.dp), contentAlignment = Alignment.Center) {
                                        val bmp = WidgetRender.badge(HabitIcons[h.habit.icon], HabitColors.of(h.habit.colorIndex), px(context, 48f), h.done, h.fraction, palette)
                                        Image(
                                            ImageProvider(bmp), contentDescription = h.habit.name,
                                            modifier = GlanceModifier.size(48.dp).clickable(toggle(h.habit.id)),
                                        )
                                    }
                                }
                                repeat(cols - rowItems.size) { Box(GlanceModifier.defaultWeight().height(cell.dp)) {} }
                            }
                        }
                    }
                    if (overflow > 0) {
                        Text("+$overflow more in the app", style = style(palette.muted, 10), modifier = GlanceModifier.padding(start = 6.dp, top = 2.dp).clickable(openApp()))
                    }
                }
            }
        }
    }
}

/* =========================================================================================
 * 3. Today's habits – list with ripple trails and tap-to-complete
 * ======================================================================================= */

class HabitsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HabitsWidget()
}

class HabitsWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadWidgetData(context)
        provideContent { Content(data) }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { Content(sampleData(context)) }
    }

    @Composable
    private fun Content(data: WidgetData) {
        val size = LocalSize.current
        val context = LocalContext.current
        val palette = WidgetRender.Palette(data.dark)
        val radius = 24.dp
        val bg = WidgetRender.card(px(context, size.width.value / 2), px(context, size.height.value / 2), palette, px(context, radius.value / 2).toFloat())
        val trailDots = ((size.width.value - 210) / 11f).toInt().coerceIn(0, TRAIL_DAYS)
        val showTrail = trailDots >= 5

        Box(modifier = GlanceModifier.fillMaxSize().cornerRadius(radius)) {
            Image(ImageProvider(bg), contentDescription = null, modifier = GlanceModifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Column(modifier = GlanceModifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row(modifier = GlanceModifier.fillMaxWidth().clickable(openApp()), verticalAlignment = Alignment.CenterVertically) {
                    Image(ImageProvider(WidgetRender.miniSun(px(context, 22f), data.progress, data.today)), contentDescription = null, modifier = GlanceModifier.size(22.dp))
                    Spacer(GlanceModifier.width(8.dp))
                    Text("Today", style = style(palette.text, 14, FontWeight.Bold))
                    Spacer(GlanceModifier.defaultWeight())
                    Text(
                        if (data.total == 0) "" else "${data.done}/${data.total}",
                        style = style(if (data.dark) Gold else Color(0xFFD9642A), 13, FontWeight.Bold),
                    )
                }
                Spacer(GlanceModifier.height(4.dp))
                if (data.habits.isEmpty()) {
                    Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(if (data.total == 0) "Add a habit to begin" else "Nothing due today. Enjoy the calm.", style = style(palette.muted, 12))
                    }
                } else {
                    LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                        items(data.habits, itemId = { it.habit.id }) { h ->
                            val color = HabitColors.of(h.habit.colorIndex)
                            Row(
                                modifier = GlanceModifier.fillMaxWidth().padding(vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Image(
                                    ImageProvider(WidgetRender.tile(HabitIcons[h.habit.icon], color, px(context, 34f), h.done)),
                                    contentDescription = null,
                                    modifier = GlanceModifier.size(34.dp).clickable(openHabit(h.habit.id)),
                                )
                                Spacer(GlanceModifier.width(10.dp))
                                Column(modifier = GlanceModifier.defaultWeight().clickable(openHabit(h.habit.id))) {
                                    Text(
                                        h.habit.name,
                                        style = style(if (h.done) palette.muted else palette.text, 13, FontWeight.Bold),
                                        maxLines = 1,
                                    )
                                    Text(h.subtitle(), style = style(palette.muted, 11), maxLines = 1)
                                }
                                if (showTrail) {
                                    Spacer(GlanceModifier.width(8.dp))
                                    val dots = h.trail.takeLast(trailDots)
                                    Image(
                                        ImageProvider(WidgetRender.trail(dots, color, px(context, 8f), px(context, 3f), palette)),
                                        contentDescription = null,
                                        modifier = GlanceModifier.width((dots.size * 11 - 3).dp).height(8.dp),
                                    )
                                }
                                Spacer(GlanceModifier.width(10.dp))
                                Image(
                                    ImageProvider(WidgetRender.check(color, px(context, 28f), h.done, h.fraction, palette)),
                                    contentDescription = if (h.done) "Done" else "Mark done",
                                    modifier = GlanceModifier.size(28.dp).clickable(toggle(h.habit.id)),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/* =========================================================================================
 * 4. Rhythm – the month as a grid of sunrise dots
 * ======================================================================================= */

class RhythmWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = RhythmWidget()
}

class RhythmWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadWidgetData(context)
        provideContent { Content(data) }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { Content(sampleData(context)) }
    }

    @Composable
    private fun Content(data: WidgetData) {
        val size: DpSize = LocalSize.current
        val context = LocalContext.current
        val palette = WidgetRender.Palette(data.dark)
        val radius = 24.dp
        val bg = WidgetRender.card(px(context, size.width.value / 2), px(context, size.height.value / 2), palette, px(context, radius.value / 2).toFloat(), accent = Gold)
        val tall = size.height >= 140.dp
        val headerH = if (tall) 46f else 30f
        val gridW = (size.width.value - 28f).coerceAtLeast(40f)
        val gridH = (size.height.value - 24f - headerH).coerceAtLeast(40f)
        val grid = WidgetRender.monthGrid(px(context, gridW), px(context, gridH), data.month, data.weekStart, data.today, data.monthDays, palette)

        Box(modifier = GlanceModifier.fillMaxSize().cornerRadius(radius).clickable(openHistory())) {
            Image(ImageProvider(bg), contentDescription = null, modifier = GlanceModifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Column(modifier = GlanceModifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(data.month.month.getDisplayName(JavaTextStyle.FULL, Locale.getDefault()), style = style(palette.text, 14, FontWeight.Bold))
                    Spacer(GlanceModifier.defaultWeight())
                    Text("${(data.monthAverage * 100).roundToInt()}%", style = style(if (data.dark) Gold else Color(0xFFB98A00), 13, FontWeight.Bold))
                }
                if (tall) {
                    Text(
                        buildString {
                            append("${data.perfectDays} perfect ${if (data.perfectDays == 1) "day" else "days"}")
                            if (data.perfectStreak > 0) append(" · ${data.perfectStreak} in a row")
                        },
                        style = style(palette.muted, 11),
                        maxLines = 1,
                    )
                }
                Spacer(GlanceModifier.height(4.dp))
                Image(
                    ImageProvider(grid), contentDescription = "Month progress grid",
                    modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                    contentScale = ContentScale.Fit,
                )
            }
        }
    }
}
