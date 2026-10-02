package com.repdayrise.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.repdayrise.app.ui.theme.LocalIsDark
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters

/** Number of intensity steps above "empty", as on a GitHub contribution graph. */
private const val LEVELS = 4

/** Buckets a 0..1 fraction into 0 (nothing) … [LEVELS] (goal met). */
fun contributionLevel(fraction: Float): Int = when {
    fraction <= 0f -> 0
    fraction >= 0.999f -> LEVELS
    else -> 1 + (fraction * (LEVELS - 1)).toInt().coerceIn(0, LEVELS - 2)
}

@Composable
private fun levelColor(level: Int, color: Color): Color {
    val dark = LocalIsDark.current
    return when (level) {
        0 -> MaterialTheme.colorScheme.surfaceContainerHighest
        1 -> color.copy(alpha = if (dark) 0.26f else 0.28f)
        2 -> color.copy(alpha = if (dark) 0.46f else 0.50f)
        3 -> color.copy(alpha = if (dark) 0.68f else 0.74f)
        else -> color
    }
}

/**
 * A year of days laid out GitHub-style: one column per week, one row per weekday, each cell
 * shaded by how much of that day was completed. Scrolls horizontally and opens on the present.
 *
 * @param values epochDay -> fraction in 0..1 for that day. Missing days count as empty.
 * @param firstDay days before this are drawn faintly (the habit didn't exist yet).
 * @param summary caption shown while no day is selected.
 * @param describe caption for a tapped day.
 */
@Composable
fun ContributionGraph(
    values: Map<Long, Float>,
    today: LocalDate,
    weekStart: DayOfWeek,
    color: Color,
    summary: String,
    describe: (LocalDate) -> String,
    modifier: Modifier = Modifier,
    firstDay: LocalDate? = null,
    weeks: Int = 53,
    cell: Dp = 13.dp,
    gap: Dp = 3.dp,
) {
    val lastWeekStart = remember(today, weekStart) { today.with(TemporalAdjusters.previousOrSame(weekStart)) }
    val origin = remember(lastWeekStart, weeks) { lastWeekStart.minusWeeks((weeks - 1).toLong()) }
    var selected by remember(origin) { mutableStateOf<LocalDate?>(null) }

    val palette = (0..LEVELS).map { levelColor(it, color) }
    val full = remember(color) { lerp(color, Color.White, 0.22f) }
    val labelStyle = MaterialTheme.typography.labelSmall
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val ring = MaterialTheme.colorScheme.onSurface
    val measurer = rememberTextMeasurer()
    val locale = LocalConfiguration.current.locales[0]
    val monthRow = 18.dp
    val scroll = rememberScrollState(Int.MAX_VALUE)

    // Columns wash in from the oldest week to the newest the first time the graph appears.
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(Unit) { reveal.animateTo(1f, tween(900)) }

    Column(modifier) {
        Row {
            // Weekday rail: every other row labelled, like GitHub.
            Column(Modifier.padding(top = monthRow, end = 6.dp), verticalArrangement = Arrangement.spacedBy(gap)) {
                for (r in 0 until 7) {
                    Box(Modifier.height(cell), contentAlignment = Alignment.CenterStart) {
                        if (r % 2 == 1) {
                            Text(
                                weekStart.plus(r.toLong()).getDisplayName(TextStyle.SHORT, locale),
                                style = labelStyle, color = labelColor,
                            )
                        }
                    }
                }
            }
            val fade = 14.dp
            Box(
                Modifier
                    .weight(1f)
                    // Cells dissolve at whichever edge has more to scroll to, instead of being cut off.
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        val f = fade.toPx()
                        if (scroll.value > 0) {
                            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), endX = f), size = Size(f, size.height), blendMode = BlendMode.DstIn)
                        }
                        if (scroll.value < scroll.maxValue) {
                            drawRect(
                                Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - f, endX = size.width),
                                topLeft = Offset(size.width - f, 0f), size = Size(f, size.height), blendMode = BlendMode.DstIn,
                            )
                        }
                    }
                    .horizontalScroll(scroll),
            ) {
                val pitch = cell + gap
                Canvas(
                    Modifier
                        .size(width = pitch * weeks - gap, height = monthRow + pitch * 7 - gap)
                        .pointerInput(origin, today) {
                            detectTapGestures { pos ->
                                val p = pitch.toPx()
                                val col = (pos.x / p).toInt()
                                val row = ((pos.y - monthRow.toPx()) / p).toInt()
                                if (col in 0 until weeks && row in 0..6 && pos.y >= monthRow.toPx()) {
                                    val date = origin.plusDays(col * 7L + row)
                                    if (!date.isAfter(today)) selected = if (selected == date) null else date
                                }
                            }
                        },
                ) {
                    val c = cell.toPx()
                    val p = pitch.toPx()
                    val top = monthRow.toPx()
                    val radius = CornerRadius(c * 0.3f)
                    var lastLabelRight = -1f
                    for (w in 0 until weeks) {
                        val weekDate = origin.plusDays(w * 7L)
                        val x = w * p
                        // Month label above the first column that contains the 1st of a month.
                        val monthStart = (0..6).map { weekDate.plusDays(it.toLong()) }.firstOrNull { it.dayOfMonth == 1 }
                        if ((monthStart != null || w == 0) && x > lastLabelRight) {
                            val label = (monthStart ?: weekDate).month.getDisplayName(TextStyle.SHORT, locale)
                            val layout = measurer.measure(label, labelStyle)
                            if (x + layout.size.width <= size.width) {
                                drawText(layout, labelColor, Offset(x, 0f))
                                lastLabelRight = x + layout.size.width + 4.dp.toPx()
                            }
                        }
                        val colAlpha = ((reveal.value * (weeks + 12) - w) / 12f).coerceIn(0f, 1f)
                        if (colAlpha <= 0f) continue
                        for (r in 0..6) {
                            val date = weekDate.plusDays(r.toLong())
                            if (date.isAfter(today)) break
                            val topLeft = Offset(x, top + r * p)
                            val level = contributionLevel(values[date.toEpochDay()] ?: 0f)
                            val before = firstDay != null && date.isBefore(firstDay)
                            val cellSize = Size(c, c)
                            if (level == LEVELS) {
                                drawRoundRect(
                                    Brush.linearGradient(listOf(full, color), start = topLeft, end = Offset(topLeft.x + c, topLeft.y + c)),
                                    topLeft, cellSize, radius, alpha = colAlpha,
                                )
                            } else {
                                drawRoundRect(palette[level], topLeft, cellSize, radius, alpha = colAlpha * if (before && level == 0) 0.4f else 1f)
                            }
                            if (date == selected) {
                                drawRoundRect(ring, topLeft, cellSize, radius, style = Stroke(1.5.dp.toPx()))
                            } else if (date == today) {
                                drawRoundRect(color.copy(alpha = 0.8f), topLeft, cellSize, radius, style = Stroke(1.dp.toPx()), alpha = colAlpha)
                            }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            AnimatedContent(
                targetState = selected,
                transitionSpec = { fadeIn(tween(160)) togetherWith fadeOut(tween(120)) },
                modifier = Modifier.weight(1f),
                label = "graphCaption",
            ) { sel ->
                Text(
                    if (sel == null) summary else describe(sel),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (sel == null) labelColor else MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.width(8.dp))
            Text("Less", style = labelStyle, color = labelColor)
            Spacer(Modifier.width(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                palette.forEachIndexed { i, swatch ->
                    Box(
                        Modifier.size(10.dp).clip(RoundedCornerShape(3.dp))
                            .then(if (i == LEVELS) Modifier.background(Brush.linearGradient(listOf(full, color))) else Modifier.background(swatch)),
                    )
                }
            }
            Spacer(Modifier.width(4.dp))
            Text("More", style = labelStyle, color = labelColor)
        }
    }
}
