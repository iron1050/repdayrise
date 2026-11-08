package com.repdayrise.app.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.repdayrise.app.ui.theme.LocalIsDark
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** Round translucent button that sits on a sky or a tinted header, like Dayrise's corner controls. */
@Composable
fun GlassCircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = Color.White.copy(alpha = 0.16f),
        contentColor = Color.White,
        modifier = modifier.size(size).glassBorder(CircleShape, alpha = 0.22f),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(22.dp))
        }
    }
}

/** Centred `Today, 27 Oct` header: the relative day in full white, the date dimmed after it. */
@Composable
fun DayTitle(date: LocalDate, today: LocalDate, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    val relative = when (date) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        today.plusDays(1) -> "Tomorrow"
        else -> date.format(DateTimeFormatter.ofPattern("EEEE"))
    }
    val text = buildAnnotatedString {
        append(relative)
        withStyle(SpanStyle(color = Color.White.copy(alpha = 0.62f))) { append(", " + date.format(DateTimeFormatter.ofPattern("d MMM"))) }
    }
    Row(modifier, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1)
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/** Dark floating surface colour shared by the bottom bar pieces. */
@Composable
private fun barColor(): Color =
    if (LocalIsDark.current) Color(0xFF1C2030).copy(alpha = 0.92f) else MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.96f)

/** One round end button of the floating bar; [selected] lifts it so the current view reads at a glance. */
@Composable
fun FloatingBarButton(icon: ImageVector, contentDescription: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val base = barColor()
    val bg by animateColorAsState(if (selected) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.22f).compositeOver(base) else base, label = "barbtn")
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = bg,
        contentColor = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .size(52.dp)
            .shadow(16.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.5f), spotColor = Color.Black.copy(alpha = 0.5f))
            .glassBorder(CircleShape, alpha = 0.08f),
    ) {
        Box(contentAlignment = Alignment.Center) { Icon(icon, contentDescription = contentDescription, modifier = Modifier.size(24.dp)) }
    }
}

/** Centre pill of the floating bar: `☀ Today ⌃⌄`. The caller anchors its menu to it. */
@Composable
fun FloatingBarPill(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = barColor(),
        contentColor = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .height(52.dp)
            .shadow(16.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.5f), spotColor = Color.Black.copy(alpha = 0.5f))
            .glassBorder(CircleShape, alpha = 0.08f),
    ) {
        Row(Modifier.padding(start = 18.dp, end = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(10.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(6.dp))
            Icon(Icons.Rounded.UnfoldMore, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Space scrolling content leaves below itself so its last item clears the floating bar. */
val FloatingBarClearance: Dp = 88.dp
