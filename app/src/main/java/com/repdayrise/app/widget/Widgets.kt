package com.repdayrise.app.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.Button
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
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
import com.repdayrise.app.R
import com.repdayrise.app.container
import com.repdayrise.app.data.model.Habit
import com.repdayrise.app.data.model.HabitType
import com.repdayrise.app.domain.MoonPhase
import com.repdayrise.app.ui.components.SkyAssets
import com.repdayrise.app.ui.components.drawSky
import com.repdayrise.app.ui.theme.HabitColors
import java.time.LocalDate
import kotlin.math.roundToInt

class WidgetUpdater(private val context: Context) {
    suspend fun updateAll() {
        runCatching { SunriseWidget().updateAll(context) }
        runCatching { HabitsWidget().updateAll(context) }
    }
}

fun renderSkyBitmap(widthPx: Int, heightPx: Int, progress: Float, date: LocalDate): Bitmap {
    val w = widthPx.coerceAtLeast(8)
    val h = heightPx.coerceAtLeast(8)
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = androidx.compose.ui.graphics.Canvas(android.graphics.Canvas(bitmap))
    CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(w.toFloat(), h.toFloat())) {
        drawSky(progress, MoonPhase.phase(date), time = 0f, stars = SkyAssets.stars, hillFraction = 0.26f, detail = false)
    }
    return bitmap
}

private data class WidgetData(
    val habits: List<Habit>,
    val done: Int,
    val total: Int,
    val progress: Float,
    val completedIds: Set<Long>,
    val values: Map<Long, Double>,
)

private suspend fun loadWidgetData(context: Context): WidgetData {
    val container = context.container
    val logic = container.logicFromSettings()
    val today = LocalDate.now()
    val habits = container.repository.getAllHabitsOnce().filter { !it.archived }
    val entries = container.repository.getEntriesOnce()
    val due = habits.filter { logic.isDue(it, entries, today) }
    val (done, total) = logic.dayCounts(habits, entries, today)
    val completed = due.filter { logic.isCompleted(it, entries, today) }.map { it.id }.toSet()
    val values = due.associate { it.id to logic.value(entries, it.id, today) }
    return WidgetData(due, done, total, logic.dayProgress(habits, entries, today), completed, values)
}

class SunriseWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SunriseWidget()
}

class SunriseWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadWidgetData(context)
        provideContent {
            GlanceTheme { SunriseContent(data) }
        }
    }

    @Composable
    private fun SunriseContent(data: WidgetData) {
        val size = LocalSize.current
        val context = LocalContext.current
        val density = context.resources.displayMetrics.density
        val bitmap = remember(size, data.progress) {
            renderSkyBitmap((size.width.value * density).roundToInt(), (size.height.value * density).roundToInt(), data.progress, LocalDate.now())
        }
        Box(
            modifier = GlanceModifier.fillMaxSize().cornerRadius(24.dp).clickable(actionStartActivity<MainActivity>()),
        ) {
            Image(ImageProvider(bitmap), contentDescription = null, modifier = GlanceModifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Column(modifier = GlanceModifier.fillMaxSize().padding(14.dp)) {
                Text(
                    text = "Today",
                    style = TextStyle(color = ColorProvider(Color.White.copy(alpha = 0.85f)), fontSize = 12.sp, fontWeight = FontWeight.Medium),
                )
                Text(
                    text = "${(data.progress * 100).roundToInt()}%",
                    style = TextStyle(color = ColorProvider(Color.White), fontSize = 30.sp, fontWeight = FontWeight.Bold),
                )
                Text(
                    text = if (data.total == 0) "No habits yet" else "${data.done} of ${data.total} done",
                    style = TextStyle(color = ColorProvider(Color.White.copy(alpha = 0.9f)), fontSize = 12.sp, fontWeight = FontWeight.Medium),
                )
            }
        }
    }
}

class HabitsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HabitsWidget()
}

val HabitIdKey = ActionParameters.Key<Long>("habit_id")

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
        HabitsWidget().update(context, glanceId)
        SunriseWidget().updateAll(context)
    }
}

class HabitsWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadWidgetData(context)
        provideContent {
            GlanceTheme { HabitsContent(data) }
        }
    }

    @Composable
    private fun HabitsContent(data: WidgetData) {
        val bg = ColorProvider(Color(0xFF0F1633))
        Column(
            modifier = GlanceModifier.fillMaxSize().background(bg).cornerRadius(24.dp).padding(12.dp),
        ) {
            Row(modifier = GlanceModifier.fillMaxWidth().clickable(actionStartActivity<MainActivity>()), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Dayrise",
                    style = TextStyle(color = ColorProvider(Color.White), fontSize = 14.sp, fontWeight = FontWeight.Bold),
                )
                Spacer(GlanceModifier.defaultWeight())
                Text(
                    "${data.done}/${data.total}",
                    style = TextStyle(color = ColorProvider(Color(0xFFFFC14D)), fontSize = 13.sp, fontWeight = FontWeight.Bold),
                )
            }
            Spacer(GlanceModifier.height(6.dp))
            if (data.habits.isEmpty()) {
                Box(GlanceModifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nothing due today", style = TextStyle(color = ColorProvider(Color.White.copy(alpha = 0.7f)), fontSize = 12.sp))
                }
            } else {
                LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
                    items(data.habits, itemId = { it.id }) { habit ->
                        val done = habit.id in data.completedIds
                        val color = HabitColors.of(habit.colorIndex)
                        Row(
                            modifier = GlanceModifier.fillMaxWidth().padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                habit.name,
                                style = TextStyle(
                                    color = ColorProvider(if (done) Color.White.copy(alpha = 0.5f) else Color.White),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Medium,
                                ),
                                maxLines = 1,
                                modifier = GlanceModifier.defaultWeight(),
                            )
                            if (habit.type == HabitType.COUNT && !done) {
                                val v = data.values[habit.id] ?: 0.0
                                Text(
                                    "${v.toInt()}/${habit.goal.toInt()}",
                                    style = TextStyle(color = ColorProvider(Color.White.copy(alpha = 0.6f)), fontSize = 11.sp),
                                )
                                Spacer(GlanceModifier.width(8.dp))
                            }
                            Image(
                                provider = ImageProvider(if (done) R.drawable.ic_widget_check else R.drawable.ic_widget_circle),
                                contentDescription = if (done) "Done" else "Mark done",
                                colorFilter = androidx.glance.ColorFilter.tint(ColorProvider(color)),
                                modifier = GlanceModifier.size(26.dp).clickable(
                                    actionRunCallback<ToggleHabitAction>(actionParametersOf(HabitIdKey to habit.id))
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}
