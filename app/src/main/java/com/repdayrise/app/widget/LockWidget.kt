package com.repdayrise.app.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.repdayrise.app.MainActivity
import com.repdayrise.app.container
import java.time.LocalDate
import kotlin.math.roundToInt

/**
 * One UI lock screen widget (monotone style). Samsung's lock screen tints/places it, so it draws
 * plain white text on a transparent background and shows only aggregate numbers (no habit names),
 * since anyone can see the lock screen.
 */
class LockWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LockWidget()
}

class LockWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val (done, total, progress) = load(context)
        provideContent { Content(done, total, progress) }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { Content(3, 5, 0.62f) }
    }

    private suspend fun load(context: Context): Triple<Int, Int, Float> {
        val container = context.container
        val logic = container.logicFromSettings()
        val today = LocalDate.now()
        val habits = container.repository.getAllHabitsOnce().filter { !it.archived }
        val entries = container.repository.getEntriesOnce()
        val (done, total) = logic.dayCounts(habits, entries, today)
        return Triple(done, total, logic.dayProgress(habits, entries, today))
    }

    @Composable
    private fun Content(done: Int, total: Int, progress: Float) {
        val tiny = LocalSize.current.width < 100.dp
        val white = ColorProvider(Color.White)
        val soft = ColorProvider(Color.White.copy(alpha = 0.85f))
        Column(
            modifier = GlanceModifier.fillMaxSize().padding(if (tiny) 4.dp else 8.dp).clickable(actionStartActivity<MainActivity>()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalAlignment = if (tiny) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            Text("${(progress * 100).roundToInt()}%", style = TextStyle(color = white, fontSize = (if (tiny) 18 else 22).sp, fontWeight = FontWeight.Bold))
            Text(
                when {
                    total == 0 -> if (tiny) "–" else "No habits today"
                    tiny -> "$done/$total"
                    done == total -> "All done"
                    else -> "$done of $total habits"
                },
                style = TextStyle(color = soft, fontSize = (if (tiny) 11 else 12).sp),
            )
        }
    }
}
