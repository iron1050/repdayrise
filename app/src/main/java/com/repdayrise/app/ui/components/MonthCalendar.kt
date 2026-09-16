package com.repdayrise.app.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

/**
 * Month grid with navigation. [cell] renders each day (null for padding cells).
 */
@Composable
fun MonthCalendar(
    month: YearMonth,
    weekStart: DayOfWeek,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    canGoNext: Boolean = true,
    header: @Composable RowScopeHeader.() -> Unit = { DefaultHeader(month, onPrevious, onNext, canGoNext) },
    cell: @Composable (LocalDate?) -> Unit,
) {
    Column(modifier) {
        RowScopeHeader.header()
        Spacer(Modifier.height(6.dp))
        val days = (0..6).map { weekStart.plus(it.toLong()) }
        Row(Modifier.fillMaxWidth()) {
            days.forEach {
                Text(
                    it.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        AnimatedContent(
            targetState = month,
            transitionSpec = {
                val forward = targetState > initialState
                (slideInHorizontally { if (forward) it / 3 else -it / 3 } + fadeIn()) togetherWith
                    (slideOutHorizontally { if (forward) -it / 3 else it / 3 } + fadeOut())
            },
            label = "month",
        ) { m ->
            val first = m.atDay(1)
            val offset = ((first.dayOfWeek.value - weekStart.value) + 7) % 7
            val total = offset + m.lengthOfMonth()
            val rows = (total + 6) / 7
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (r in 0 until rows) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        for (c in 0 until 7) {
                            val index = r * 7 + c - offset
                            val date = if (index in 0 until m.lengthOfMonth()) m.atDay(index + 1) else null
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { cell(date) }
                        }
                    }
                }
            }
        }
    }
}

object RowScopeHeader

@Composable
fun DefaultHeader(month: YearMonth, onPrevious: () -> Unit, onNext: () -> Unit, canGoNext: Boolean) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            month.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + month.year,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        IconButton(onClick = onPrevious) { Icon(Icons.Rounded.ChevronLeft, contentDescription = "Previous month") }
        IconButton(onClick = onNext, enabled = canGoNext) { Icon(Icons.Rounded.ChevronRight, contentDescription = "Next month") }
    }
}
