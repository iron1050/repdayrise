package com.repdayrise.app.widget

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ContentScale
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.repdayrise.app.MainActivity
import com.repdayrise.app.container
import java.time.LocalDate
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * One UI lock screen widget (monotone style). Samsung's lock screen places it over the wallpaper,
 * so it draws only white on transparent, and shows aggregate numbers (no habit names) since anyone
 * can see the lock screen. Sizes: 1x1 tiny, 2x1 small, 2x2 medium (the sunrise arc).
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
        val size = LocalSize.current
        val context = LocalContext.current
        val white = ColorProvider(Color.White)
        val soft = ColorProvider(Color.White.copy(alpha = 0.8f))
        val percent = "${(progress * 100).roundToInt()}%"
        val open = actionStartActivity<MainActivity>()
        when {
            // 2x2: sunrise arc over the numbers.
            size.height >= 100.dp && size.width >= 100.dp -> Column(
                modifier = GlanceModifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp).clickable(open),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val arcW = size.width - 20.dp
                val arcH = arcW * 0.56f
                Image(
                    ImageProvider(sunArc(WidgetRender.dp(context, arcW.value), WidgetRender.dp(context, arcH.value), progress)),
                    contentDescription = null,
                    modifier = GlanceModifier.fillMaxWidth().height(arcH),
                    contentScale = ContentScale.Fit,
                )
                Spacer(GlanceModifier.height(6.dp))
                Text(percent, style = TextStyle(color = white, fontSize = 26.sp, fontWeight = FontWeight.Bold))
                Text(label(done, total, compact = false), style = TextStyle(color = soft, fontSize = 12.sp))
            }
            // 1x1: just the numbers, centred.
            size.width < 100.dp -> Column(
                modifier = GlanceModifier.fillMaxSize().padding(4.dp).clickable(open),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(percent, style = TextStyle(color = white, fontSize = 18.sp, fontWeight = FontWeight.Bold))
                Text(label(done, total, compact = true), style = TextStyle(color = soft, fontSize = 11.sp))
            }
            // 2x1: numbers, left-aligned.
            else -> Column(
                modifier = GlanceModifier.fillMaxSize().padding(8.dp).clickable(open),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(percent, style = TextStyle(color = white, fontSize = 22.sp, fontWeight = FontWeight.Bold))
                Text(label(done, total, compact = false), style = TextStyle(color = soft, fontSize = 12.sp))
            }
        }
    }

    private fun label(done: Int, total: Int, compact: Boolean): String = when {
        total == 0 -> if (compact) "–" else "No habits today"
        compact -> "$done/$total"
        done == total -> "All done"
        else -> "$done of $total habits"
    }

    /** The sun's path over the horizon: faint track, bright travelled arc, glowing sun at the tip. */
    private fun sunArc(widthPx: Int, heightPx: Int, progress: Float): Bitmap = WidgetRender.bitmap(widthPx, heightPx) {
        val p = progress.coerceIn(0f, 1f)
        val stroke = size.width * 0.04f
        val sunR = size.width * 0.055f
        val glowR = sunR * if (p >= 1f) 2.8f else 2.1f
        val horizonY = size.height - stroke
        val r = min(size.width / 2f - glowR, horizonY - glowR)
        val c = Offset(size.width / 2f, horizonY)
        val topLeft = Offset(c.x - r, c.y - r)
        val arcSize = Size(r * 2, r * 2)
        drawLine(
            Color.White.copy(alpha = 0.4f), Offset(c.x - r - glowR * 0.7f, horizonY), Offset(c.x + r + glowR * 0.7f, horizonY),
            strokeWidth = stroke * 0.6f, cap = StrokeCap.Round,
        )
        drawArc(Color.White.copy(alpha = 0.2f), 180f, 180f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        if (p > 0f) drawArc(Color.White, 180f, 180f * p, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
        val a = Math.toRadians(180.0 + 180.0 * p)
        val sun = Offset(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat())
        drawCircle(Color.White.copy(alpha = 0.14f), glowR, sun)
        drawCircle(Color.White.copy(alpha = 0.22f), glowR * 0.68f, sun)
        drawCircle(Color.White, sunR, sun)
    }
}
