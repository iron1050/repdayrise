package com.repdayrise.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.repdayrise.app.data.model.Habit
import com.repdayrise.app.ui.theme.HabitColors
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Dayrise-style greens/yellows/oranges used for progress levels. */
val LevelLime = Color(0xFF8BE34A)
val LevelYellow = Color(0xFFFFC107)
val LevelOrange = Color(0xFFFF7A2F)

fun levelColor(fraction: Float): Color = when {
    fraction < 0.5f -> LevelOrange
    fraction < 0.75f -> LevelYellow
    else -> LevelLime
}

data class DashDay(val date: LocalDate, val progress: Float, val isToday: Boolean, val isFuture: Boolean)

/** `[check 22] | 91% | [ring 2]` glass pill that sits on the sky. Frosting is applied by the caller. */
@Composable
fun ProgressPill(done: Int, percent: Int, left: Int, modifier: Modifier = Modifier) {
    val ink = Color.White
    Row(modifier.padding(horizontal = 22.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(ink.copy(alpha = 0.92f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Check, null, tint = Color(0xFF2B5FA8), modifier = Modifier.size(14.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text("$done", style = MaterialTheme.typography.headlineSmall, color = ink)
        }
        Box(Modifier.width(1.dp).height(30.dp).background(ink.copy(alpha = 0.3f)))
        Row(Modifier.weight(1.3f), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
            Text("$percent", style = MaterialTheme.typography.displaySmall.copy(fontSize = 40.sp, lineHeight = 44.sp), color = ink)
            Text("%", style = MaterialTheme.typography.titleLarge, color = ink.copy(alpha = 0.8f), modifier = Modifier.padding(start = 2.dp, bottom = 7.dp))
        }
        Box(Modifier.width(1.dp).height(30.dp).background(ink.copy(alpha = 0.3f)))
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            Canvas(Modifier.size(20.dp)) {
                drawCircle(
                    ink.copy(alpha = 0.9f), size.minDimension / 2 - 1.dp.toPx(),
                    style = Stroke(1.6.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))),
                )
            }
            Spacer(Modifier.width(10.dp))
            Text("$left", style = MaterialTheme.typography.headlineSmall, color = ink)
        }
    }
}

/** Seven rounded-square day tiles tinted by that day's progress; the selected one gets a white border. */
@Composable
fun DayTileStrip(days: List<DashDay>, selected: LocalDate, onSelect: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    val base = MaterialTheme.colorScheme.surfaceContainerHigh
    val light = Color(0xFF9CC4F0)
    val deep = Color(0xFF2F6BC9)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        days.forEach { d ->
            val sel = d.date == selected
            val p = if (d.isFuture) 0f else d.progress
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).clickable { onSelect(d.date) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(
                            Brush.verticalGradient(
                                listOf(lerp(base.copy(alpha = if (d.isFuture) 0.5f else 0.9f), light, p), lerp(base.copy(alpha = if (d.isFuture) 0.5f else 0.9f), deep, p)),
                            ),
                        )
                        .then(if (sel) Modifier.border(2.dp, Color.White, RoundedCornerShape(14.dp)) else Modifier),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    d.date.dayOfMonth.toString(),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (sel || d.isToday) FontWeight.ExtraBold else FontWeight.Medium,
                    color = if (sel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Radial gauge made of short ticks, lime when filled, with the percentage in the middle. */
@Composable
fun TickGauge(fraction: Float, modifier: Modifier = Modifier, ticks: Int = 40) {
    val f = fraction.coerceIn(0f, 1f)
    val off = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.16f)
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val outer = size.minDimension / 2 - 2.dp.toPx()
            val len = 11.dp.toPx()
            val c = center
            val filled = (ticks * f).roundToInt()
            for (i in 0 until ticks) {
                val a = (135f + 270f * i / (ticks - 1)) * (PI / 180f).toFloat()
                val dir = Offset(cos(a), sin(a))
                val col = if (i < filled) lerp(Color(0xFF36D96B), Color(0xFFB5F03A), i / (ticks - 1f)) else off
                drawLine(col, c + dir * (outer - len), c + dir * outer, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${(f * 100).roundToInt()}", style = MaterialTheme.typography.headlineMedium)
            Text("%", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 1.dp, bottom = 4.dp))
        }
    }
}

/** Glass tile with a small centred title, used for the 2x2 dashboard grid. */
@Composable
fun DashTile(title: String, modifier: Modifier = Modifier, glow: Color? = null, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    GlowCard(modifier = modifier, shape = MaterialTheme.shapes.large, glow = glow, onClick = onClick) {
        Column(Modifier.fillMaxWidth().padding(vertical = 14.dp, horizontal = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

/**
 * 12-month line chart: dashed 0-100% grid, ring markers coloured by level, and a tooltip on the selected month.
 * [values] holds one fraction per month, or null for months without data.
 */
@Composable
fun MonthLineChart(values: List<Float?>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f)
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
    val tipStyle = MaterialTheme.typography.labelLarge
    val bg = MaterialTheme.colorScheme.surfaceContainerHigh
    Column(modifier) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(190.dp)
                .pointerInput(values) {
                    detectTapGestures { tap ->
                        val left = 10.dp.toPx()
                        val right = size.width - 44.dp.toPx()
                        val step = (right - left) / 11f
                        val idx = ((tap.x - left) / step).roundToInt().coerceIn(0, 11)
                        if (values.getOrNull(idx) != null) onSelect(idx)
                    }
                },
        ) {
            val left = 10.dp.toPx()
            val right = size.width - 44.dp.toPx()
            val top = 30.dp.toPx()
            val bottom = size.height - 4.dp.toPx()
            val step = (right - left) / 11f
            fun y(v: Float) = bottom - v.coerceIn(0f, 1f) * (bottom - top)
            for (g in 0..5) {
                val gv = g / 5f
                drawLine(grid, Offset(left, y(gv)), Offset(right + 8.dp.toPx(), y(gv)), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 5.dp.toPx())))
                val t = measurer.measure("${g * 20}%", labelStyle)
                drawText(t, topLeft = Offset(right + 14.dp.toPx(), y(gv) - t.size.height / 2f))
            }
            val pts = values.mapIndexed { i, v -> v?.let { Offset(left + step * i, y(it)) } }
            val path = Path()
            var started = false
            pts.forEach { p ->
                if (p != null) { if (!started) { path.moveTo(p.x, p.y); started = true } else path.lineTo(p.x, p.y) }
            }
            drawPath(path, Color(0xFFE5A72E).copy(alpha = 0.85f), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            pts.forEachIndexed { i, p ->
                if (p == null) return@forEachIndexed
                val col = levelColor(values[i]!!)
                val sel = i == selected
                drawCircle(bg, if (sel) 7.dp.toPx() else 5.dp.toPx(), p)
                drawCircle(col, if (sel) 7.dp.toPx() else 5.dp.toPx(), p, style = Stroke(3.dp.toPx()))
            }
            val sp = pts.getOrNull(selected)
            val sv = values.getOrNull(selected)
            if (sp != null && sv != null) {
                val name = java.time.Month.of(selected + 1).getDisplayName(TextStyle.FULL, Locale.getDefault())
                val t = measurer.measure("$name ${(sv * 100).roundToInt()}%", tipStyle.copy(color = levelColor(sv)))
                val w = t.size.width + 20.dp.toPx()
                val h = t.size.height + 10.dp.toPx()
                val x = (sp.x - w / 2f).coerceIn(0f, size.width - w)
                val ty = (sp.y - h - 12.dp.toPx()).coerceAtLeast(0f)
                drawRoundRect(levelColor(sv).copy(alpha = 0.18f), Offset(x, ty), Size(w, h), CornerRadius(10.dp.toPx()))
                drawRoundRect(levelColor(sv).copy(alpha = 0.5f), Offset(x, ty), Size(w, h), CornerRadius(10.dp.toPx()), style = Stroke(1.dp.toPx()))
                drawText(t, topLeft = Offset(x + 10.dp.toPx(), ty + 5.dp.toPx()))
            }
        }
        Row(Modifier.fillMaxWidth().padding(start = 2.dp, end = 34.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            (1..12).forEach { m ->
                Text(
                    java.time.Month.of(m).getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (m - 1 == selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(18.dp),
                )
            }
        }
    }
}

/**
 * 12-month bar chart. [days] is the top habit's completed days per month (null = no data); each bar carries a flame
 * above it, and the selected month shows the habit's own icon instead, in its colour.
 */
@Composable
fun FlameBarChart(days: List<Pair<Habit, Int>?>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val maxDays = (days.maxOfOrNull { it?.second ?: 0 } ?: 1).coerceAtLeast(1)
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(modifier.fillMaxWidth().height(170.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Bottom) {
        days.forEachIndexed { i, entry ->
            val sel = i == selected
            val habitColor = entry?.let { HabitColors.of(it.first.colorIndex) } ?: MaterialTheme.colorScheme.primary
            Column(
                Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(8.dp)).clickable(enabled = entry != null) { onSelect(i) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                if (entry != null) {
                    if (sel) {
                        Icon(HabitIcons[entry.first.icon], null, tint = habitColor, modifier = Modifier.size(16.dp))
                    } else {
                        Icon(Icons.Rounded.LocalFireDepartment, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(14.dp))
                    }
                    Spacer(Modifier.height(4.dp))
                }
                val frac = (entry?.second ?: 0).toFloat() / maxDays
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height((96.dp * frac).coerceAtLeast(if (entry == null) 6.dp else 10.dp))
                        .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp, bottomStart = 3.dp, bottomEnd = 3.dp))
                        .background(if (entry == null) Brush.verticalGradient(listOf(track.copy(alpha = 0.5f), track.copy(alpha = 0.5f))) else if (sel) Brush.verticalGradient(listOf(lerp(habitColor, Color.White, 0.25f), habitColor)) else Brush.verticalGradient(listOf(track, track.copy(alpha = 0.7f)))),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    java.time.Month.of(i + 1).getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (sel) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                )
            }
        }
    }
}
