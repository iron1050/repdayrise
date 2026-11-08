package com.repdayrise.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * One day in the habit calendar: a filled bead with a halo when done, an arc when partly done, a hollow ring when
 * a scheduled day was missed, and a dashed ring for today.
 */
@Composable
fun CalendarDay(
    day: Int,
    color: Color,
    fraction: Float,
    completed: Boolean,
    missed: Boolean,
    isToday: Boolean,
    inactive: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
) {
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val selectedColor = MaterialTheme.colorScheme.primary
    val textColor = when {
        completed -> Color.White
        inactive -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 2.dp.toPx()
            val outer = this.size.minDimension / 2 - 1.dp.toPx()
            val c = center
            when {
                completed -> {
                    drawCircle(accentGradient(color), outer - 3.dp.toPx(), c)
                    if (!isToday) drawCircle(color.copy(alpha = 0.45f), outer, c, style = Stroke(1.5.dp.toPx()))
                }
                inactive -> Unit
                fraction > 0f -> {
                    drawCircle(track.copy(alpha = 0.7f), outer - 3.dp.toPx(), c)
                    drawCircle(color.copy(alpha = 0.22f), outer - stroke / 2, c, style = Stroke(stroke))
                    drawArc(
                        color, -90f, 360f * fraction, false,
                        Offset(c.x - outer + stroke / 2, c.y - outer + stroke / 2), Size((outer - stroke / 2) * 2, (outer - stroke / 2) * 2),
                        style = Stroke(stroke, cap = StrokeCap.Round),
                    )
                }
                missed -> {
                    drawCircle(track.copy(alpha = 0.5f), outer - 3.dp.toPx(), c)
                    drawCircle(color.copy(alpha = 0.30f), outer - 0.75.dp.toPx(), c, style = Stroke(1.5.dp.toPx()))
                }
                else -> drawCircle(track.copy(alpha = 0.7f), outer - 3.dp.toPx(), c)
            }
            if (isToday) {
                drawCircle(
                    color.copy(alpha = 0.9f), outer, c,
                    style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
                )
            }
            if (selected) drawCircle(selectedColor, outer + 1.dp.toPx(), c, style = Stroke(2.dp.toPx()))
        }
        Text(
            day.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (completed || isToday) FontWeight.Bold else FontWeight.Medium,
            color = textColor,
        )
    }
}

enum class TickState { DONE, MISSED, UPCOMING, OFF }

fun completionLevel(fraction: Float): String = when {
    fraction < 0.25f -> "Beginner"
    fraction < 0.5f -> "Rising"
    fraction < 0.9f -> "Advanced"
    else -> "Master"
}

/** The gauge block under the calendar: a ring with a level, a tick bar for the month, and day counts. */
@Composable
fun MonthGaugeBlock(fraction: Float, color: Color, ticks: List<TickState>, done: Int, incomplete: Int?, modifier: Modifier = Modifier, goalNote: String? = null) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val off = MaterialTheme.colorScheme.surfaceContainerHighest
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Gauge(
            fraction = fraction,
            color = color,
            modifier = Modifier.size(112.dp),
            label = "${(fraction * 100).roundToInt()}%",
            sublabel = completionLevel(fraction),
        )
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Canvas(Modifier.fillMaxWidth().height(18.dp)) {
                val n = ticks.size.coerceAtLeast(1)
                val gap = 2.dp.toPx()
                val w = ((size.width - gap * (n - 1)) / n).coerceAtLeast(1f)
                ticks.forEachIndexed { i, t ->
                    val x = i * (w + gap)
                    val h = if (t == TickState.DONE) size.height else size.height * 0.62f
                    val col = when (t) {
                        TickState.DONE -> color
                        TickState.MISSED -> color.copy(alpha = 0.28f)
                        TickState.UPCOMING -> off
                        TickState.OFF -> off.copy(alpha = 0.4f)
                    }
                    drawRoundRect(col, Offset(x, size.height - h), Size(w, h), CornerRadius(w / 2))
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.Check, null, tint = color, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(8.dp))
                Text("$done ${if (done == 1) "day" else "days"} completed", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(16.dp).padding(2.dp)) {
                    Canvas(Modifier.fillMaxSize()) { drawCircle(muted.copy(alpha = 0.7f), style = Stroke(1.5.dp.toPx())) }
                }
                Spacer(Modifier.width(8.dp))
                Text(if (incomplete != null) "$incomplete ${if (incomplete == 1) "day" else "days"} incomplete" else goalNote.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = muted)
            }
        }
    }
}

/** Compact pill selector (W / M / Y / ∞). */
@Composable
fun PillChips(labels: List<String>, selected: Int, color: Color, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        labels.forEachIndexed { i, l ->
            val on = i == selected
            Box(
                Modifier
                    .clip(CircleShape)
                    .background(if (on) color.copy(alpha = 0.26f) else Color.Transparent)
                    .clickable { onSelect(i) }
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(l, style = MaterialTheme.typography.labelLarge, color = if (on) color else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A label/value row for the statistics list, with an optional coloured delta under the value. */
@Composable
fun StatRow(label: String, value: String, modifier: Modifier = Modifier, delta: String? = null, deltaPositive: Boolean = true, sublabel: String? = null) {
    Row(modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (sublabel != null) Text(sublabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            if (!delta.isNullOrBlank()) {
                Text(delta, style = MaterialTheme.typography.labelMedium, color = if (deltaPositive) Color(0xFF3DDC84) else MaterialTheme.colorScheme.error)
            }
        }
    }
}
