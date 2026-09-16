package com.repdayrise.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.repdayrise.app.data.model.HabitType
import com.repdayrise.app.ui.theme.HabitColors
import kotlin.math.max

/** Rounded icon tile in the habit's colour. */
@Composable
fun IconBadge(icon: ImageVector, color: Color, modifier: Modifier = Modifier, size: Dp = 44.dp, iconSize: Dp = 22.dp, filled: Boolean = false) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(if (filled) color else color.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = if (filled) Color.White else color, modifier = Modifier.size(iconSize))
    }
}

@Composable
fun ProgressRing(
    progress: Float,
    color: Color,
    modifier: Modifier = Modifier,
    stroke: Dp = 4.dp,
    track: Color = color.copy(alpha = 0.18f),
    animate: Boolean = true,
) {
    val value by animateFloatAsState(progress.coerceIn(0f, 1f), if (animate) spring(stiffness = Spring.StiffnessLow) else tween(0), label = "ring")
    Canvas(modifier) {
        val s = stroke.toPx()
        val inset = s / 2
        val arcSize = Size(size.width - s, size.height - s)
        drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(s, cap = StrokeCap.Round))
        if (value > 0f) {
            drawArc(color, -90f, 360f * value, false, Offset(inset, inset), arcSize, style = Stroke(s, cap = StrokeCap.Round))
        }
    }
}

/**
 * The trailing control on a habit row. Tap completes / increments / starts timer; long-press opens custom value.
 */
@Composable
fun HabitControl(
    type: HabitType,
    completed: Boolean,
    fraction: Float,
    colorIndex: Int,
    timerRunning: Boolean = false,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 46.dp,
) {
    val color = HabitColors.of(colorIndex)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.88f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium), label = "press")
    val fill by animateFloatAsState(if (completed) 1f else 0f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow), label = "fill")

    Box(
        modifier
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .combinedClickable(
                interactionSource = interaction,
                indication = ripple(bounded = true, color = color),
                onClick = onClick,
                onLongClick = onLongClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        // Base ring / track
        ProgressRing(
            progress = if (completed) 1f else fraction,
            color = color,
            modifier = Modifier.fillMaxSize().padding(2.dp),
            stroke = if (type == HabitType.CHECK) 2.5.dp else 4.dp,
            track = if (type == HabitType.CHECK) color.copy(alpha = 0.35f) else color.copy(alpha = 0.18f),
        )
        // Filled disc when complete
        Box(
            Modifier
                .size(size - 4.dp)
                .scale(fill)
                .clip(CircleShape)
                .background(color),
        )
        AnimatedVisibility(visible = completed, enter = scaleIn(spring(dampingRatio = 0.5f)) + fadeIn(), exit = scaleOut() + fadeOut()) {
            Icon(Icons.Rounded.Check, contentDescription = "Completed", tint = Color.White, modifier = Modifier.size(size * 0.5f))
        }
        AnimatedVisibility(visible = !completed, enter = fadeIn(), exit = fadeOut()) {
            val icon = when (type) {
                HabitType.CHECK -> null
                HabitType.COUNT -> Icons.Rounded.Add
                HabitType.TIMER -> if (timerRunning) Icons.Rounded.Pause else Icons.Rounded.PlayArrow
            }
            if (icon != null) Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(size * 0.46f))
        }
    }
}

@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier, accent: Color = MaterialTheme.colorScheme.primary) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(value, style = MaterialTheme.typography.headlineSmall, color = accent, textAlign = TextAlign.Center)
            Spacer(Modifier.height(2.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** Arc gauge showing a fraction with a label in the middle. */
@Composable
fun Gauge(fraction: Float, color: Color, modifier: Modifier = Modifier, label: String, sublabel: String) {
    val value by animateFloatAsState(fraction.coerceIn(0f, 1f), spring(stiffness = Spring.StiffnessLow), label = "gauge")
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = 14.dp.toPx()
            val d = size.minDimension - s
            val topLeft = Offset((size.width - d) / 2, (size.height - d) / 2 + s * 0.4f)
            drawArc(track, 135f, 270f, false, topLeft, Size(d, d), style = Stroke(s, cap = StrokeCap.Round))
            if (value > 0f) drawArc(color, 135f, 270f * value, false, topLeft, Size(d, d), style = Stroke(s, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurface)
            Text(sublabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Simple rounded bar chart. */
@Composable
fun BarChart(
    values: List<Float>,
    labels: List<String>,
    color: Color,
    modifier: Modifier = Modifier,
    highlight: Int = -1,
    maxValue: Float? = null,
) {
    val progress by animateFloatAsState(1f, spring(stiffness = Spring.StiffnessLow), label = "bars")
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val labelStyle = MaterialTheme.typography.labelSmall
    Column(modifier) {
        val maxV = max(maxValue ?: (values.maxOrNull() ?: 1f), 0.0001f)
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val n = values.size
            if (n == 0) return@Canvas
            val gap = if (n > 16) 2.dp.toPx() else 6.dp.toPx()
            val barW = (size.width - gap * (n - 1)) / n
            val radius = androidx.compose.ui.geometry.CornerRadius(barW / 2.5f)
            values.forEachIndexed { i, v ->
                val x = i * (barW + gap)
                drawRoundRect(track, Offset(x, 0f), Size(barW, size.height), radius)
                val hgt = (v / maxV).coerceIn(0f, 1f) * size.height * progress
                if (hgt > 0f) {
                    val c = if (highlight == i) color else color.copy(alpha = 0.75f)
                    drawRoundRect(c, Offset(x, size.height - hgt), Size(barW, hgt), radius)
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val n = values.size
            if (n > 0) {
                val step = if (n > 16) max(1, n / 6) else 1
                labels.forEachIndexed { i, l ->
                    if (n <= 16) {
                        Text(l, style = labelStyle, color = muted, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                    }
                }
                if (n > 16) {
                    labels.filterIndexed { i, _ -> i % step == 0 }.forEach { Text(it, style = labelStyle, color = muted) }
                }
            }
        }
    }
}
