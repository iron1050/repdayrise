package com.repdayrise.app.widget

import android.graphics.Bitmap
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.repdayrise.app.domain.MoonPhase
import com.repdayrise.app.ui.components.SkyAssets
import com.repdayrise.app.ui.components.SkyPalette
import com.repdayrise.app.ui.components.accentGradient
import com.repdayrise.app.ui.components.drawSky
import com.repdayrise.app.ui.theme.Gold
import com.repdayrise.app.ui.theme.Indigo
import com.repdayrise.app.ui.theme.Sunrise
import com.repdayrise.app.ui.theme.SunriseDeep
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Off-screen rendering for widgets. Glance can only show flat colours and images, so every
 * gradient, ring and icon the widgets show is drawn here into a bitmap first.
 */
object WidgetRender {

    /** Widget palette: a night card and a cream card, echoing the app's two themes. */
    class Palette(val dark: Boolean) {
        val card: Color = if (dark) Color(0xFF12172F) else Color(0xFFFBF9F4)
        val cardHigh: Color = if (dark) Color(0xFF1E2546) else Color(0xFFFFFFFF)
        val text: Color = if (dark) Color(0xFFF2F1F8) else Color(0xFF1A1B2E)
        val muted: Color = if (dark) Color(0xFFA9ACC7) else Color(0xFF5E6078)
        val faint: Color = if (dark) Color(0xFF3A4066) else Color(0xFFE6E1D7)
        val track: Color = if (dark) Color(0xFF2B3158) else Color(0xFFECE7DD)
    }

    fun bitmap(widthPx: Int, heightPx: Int, block: DrawScope.() -> Unit): Bitmap {
        val w = widthPx.coerceAtLeast(4)
        val h = heightPx.coerceAtLeast(4)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = androidx.compose.ui.graphics.Canvas(android.graphics.Canvas(bmp))
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, canvas, Size(w.toFloat(), h.toFloat())) { block() }
        return bmp
    }

    /** Living sky for the sunrise widget. */
    fun sky(widthPx: Int, heightPx: Int, progress: Float, date: LocalDate, radiusPx: Float): Bitmap =
        bitmap(widthPx, heightPx) {
            roundedClip(radiusPx) {
                drawSky(progress, MoonPhase.phase(date), time = 0f, stars = SkyAssets.stars, hillFraction = 0.26f, detail = false)
                // Bottom scrim so white text stays legible on bright days.
                drawRect(Brush.verticalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.28f), startY = size.height * 0.45f, endY = size.height))
            }
        }

    /** Ambient card background with the same soft glows as the app. Rendered at low res; it is a blur anyway. */
    fun card(widthPx: Int, heightPx: Int, palette: Palette, radiusPx: Float, accent: Color? = null): Bitmap =
        bitmap(widthPx, heightPx) {
            roundedClip(radiusPx) {
                drawRect(palette.card)
                val w = size.width
                val h = size.height
                val warm = accent ?: Sunrise
                val warmAlpha = if (palette.dark) 0.26f else 0.18f
                val coolAlpha = if (palette.dark) 0.34f else 0.10f
                drawCircle(
                    Brush.radialGradient(0f to warm.copy(alpha = warmAlpha), 1f to warm.copy(alpha = 0f), center = Offset(w * 0.95f, -h * 0.1f), radius = w * 0.7f),
                    radius = w * 0.7f, center = Offset(w * 0.95f, -h * 0.1f),
                )
                drawCircle(
                    Brush.radialGradient(0f to Indigo.copy(alpha = coolAlpha), 1f to Indigo.copy(alpha = 0f), center = Offset(w * 0.05f, h * 1.05f), radius = w * 0.8f),
                    radius = w * 0.8f, center = Offset(w * 0.05f, h * 1.05f),
                )
                if (palette.dark) {
                    drawCircle(
                        Brush.radialGradient(0f to SunriseDeep.copy(alpha = 0.10f), 1f to SunriseDeep.copy(alpha = 0f), center = Offset(w * 0.8f, h * 0.9f), radius = w * 0.5f),
                        radius = w * 0.5f, center = Offset(w * 0.8f, h * 0.9f),
                    )
                }
                // Top highlight + hairline border, like the in-app glass cards.
                drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = if (palette.dark) 0.05f else 0.55f), 1f to Color.Transparent, endY = h * 0.4f))
                drawRoundRect(
                    Color.White.copy(alpha = if (palette.dark) 0.08f else 0.9f),
                    topLeft = Offset(0.75f, 0.75f), size = Size(w - 1.5f, h - 1.5f),
                    cornerRadius = CornerRadius(radiusPx - 0.75f), style = Stroke(1.5f),
                )
            }
        }

    /**
     * Habit badge used as a tap target. Done: gradient-filled disc with a white icon.
     * Not done: progress ring around a faint disc with the coloured icon.
     */
    fun badge(vector: ImageVector, color: Color, sizePx: Int, done: Boolean, fraction: Float, palette: Palette, ring: Boolean = true): Bitmap =
        bitmap(sizePx, sizePx) {
            val s = size.minDimension
            val c = Offset(s / 2, s / 2)
            val stroke = s * 0.075f
            if (done) {
                drawCircle(accentGradient(color), radius = s / 2 - 1f, center = c)
                drawCircle(
                    Brush.radialGradient(0f to Color.White.copy(alpha = 0.35f), 1f to Color.Transparent, center = Offset(s * 0.34f, s * 0.3f), radius = s * 0.5f),
                    radius = s * 0.5f, center = Offset(s * 0.34f, s * 0.3f),
                )
                drawIcon(vector, s * 0.5f, Color.White, Offset(s * 0.25f, s * 0.25f))
            } else {
                drawCircle(color.copy(alpha = if (palette.dark) 0.16f else 0.13f), radius = s / 2 - stroke, center = c)
                if (ring) {
                    val inset = stroke / 2 + 0.5f
                    val arc = Size(s - inset * 2, s - inset * 2)
                    drawArc(color.copy(alpha = 0.22f), 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
                    if (fraction > 0.005f) {
                        drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
                    }
                }
                drawIcon(vector, s * 0.48f, color, Offset(s * 0.26f, s * 0.26f))
            }
        }

    /** Small circular icon tile (no ring) for list rows. */
    fun tile(vector: ImageVector, color: Color, sizePx: Int, filled: Boolean): Bitmap =
        bitmap(sizePx, sizePx) {
            val s = size.minDimension
            if (filled) {
                drawCircle(accentGradient(color), radius = s / 2, center = Offset(s / 2, s / 2))
                drawIcon(vector, s * 0.52f, Color.White, Offset(s * 0.24f, s * 0.24f))
            } else {
                drawCircle(color.copy(alpha = 0.16f), radius = s / 2, center = Offset(s / 2, s / 2))
                drawIcon(vector, s * 0.52f, color, Offset(s * 0.24f, s * 0.24f))
            }
        }

    /** Simple check circle (used on the trailing end of list rows). */
    fun check(color: Color, sizePx: Int, done: Boolean, fraction: Float, palette: Palette): Bitmap =
        bitmap(sizePx, sizePx) {
            val s = size.minDimension
            val c = Offset(s / 2, s / 2)
            val stroke = s * 0.09f
            if (done) {
                drawCircle(accentGradient(color), radius = s / 2 - 1f, center = c)
                // check mark
                val p = androidx.compose.ui.graphics.Path().apply {
                    moveTo(s * 0.29f, s * 0.52f); lineTo(s * 0.44f, s * 0.67f); lineTo(s * 0.72f, s * 0.36f)
                }
                drawPath(p, Color.White, style = Stroke(s * 0.1f, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
            } else {
                val inset = stroke / 2 + 0.5f
                val arc = Size(s - inset * 2, s - inset * 2)
                drawArc(color.copy(alpha = 0.28f), 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
                if (fraction > 0.005f) drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
            }
        }

    /**
     * Ripple trail: one dot per recent day, oldest on the left. [days] holds the fraction done
     * (or null when the habit wasn't scheduled that day).
     */
    fun trail(days: List<Float?>, color: Color, dotPx: Int, gapPx: Int, palette: Palette): Bitmap {
        val n = days.size
        val w = n * dotPx + (n - 1).coerceAtLeast(0) * gapPx
        return bitmap(w, dotPx) {
            val r = dotPx / 2f
            days.forEachIndexed { i, f ->
                val cx = i * (dotPx + gapPx) + r
                when {
                    f == null -> drawCircle(palette.track, radius = r * 0.32f, center = Offset(cx, r))
                    f >= 0.999f -> drawCircle(accentGradient(color), radius = r, center = Offset(cx, r))
                    f > 0.005f -> {
                        drawCircle(color.copy(alpha = 0.18f), radius = r, center = Offset(cx, r))
                        drawCircle(color.copy(alpha = 0.35f + 0.55f * f), radius = r * (0.45f + 0.5f * f), center = Offset(cx, r))
                    }
                    else -> drawCircle(palette.track, radius = r, center = Offset(cx, r))
                }
            }
        }
    }

    /**
     * Month rhythm grid: calendar-shaped dot grid where each dot's colour is that day's sunrise
     * progress. Rows are weeks. [days] maps day-of-month to progress (missing = no habits).
     */
    fun monthGrid(
        widthPx: Int,
        heightPx: Int,
        month: java.time.YearMonth,
        weekStart: java.time.DayOfWeek,
        today: LocalDate,
        days: Map<Int, Float>,
        palette: Palette,
    ): Bitmap = bitmap(widthPx, heightPx) {
        val first = month.atDay(1)
        val offset = ((first.dayOfWeek.value - weekStart.value) + 7) % 7
        val total = offset + month.lengthOfMonth()
        val rows = (total + 6) / 7
        val cellW = size.width / 7f
        val cellH = size.height / rows
        val dot = min(cellW, cellH) * 0.72f
        val r = dot / 2
        for (index in 0 until month.lengthOfMonth()) {
            val date = month.atDay(index + 1)
            val slot = index + offset
            val row = slot / 7
            val col = slot % 7
            val c = Offset(col * cellW + cellW / 2, row * cellH + cellH / 2)
            val future = date.isAfter(today)
            val p = days[index + 1]
            when {
                future -> drawCircle(palette.track.copy(alpha = 0.55f), radius = r * 0.45f, center = c)
                p == null -> drawCircle(palette.track, radius = r * 0.6f, center = c)
                else -> {
                    val fill = progressColor(p)
                    drawCircle(palette.track, radius = r, center = c)
                    if (p > 0.005f) {
                        drawCircle(
                            Brush.radialGradient(0f to lerp(fill, Color.White, 0.25f), 1f to fill, center = c - Offset(r * 0.25f, r * 0.3f), radius = r * 1.2f),
                            radius = r * (0.35f + 0.65f * p), center = c,
                        )
                    }
                }
            }
            if (date == today) {
                drawCircle(Sunrise, radius = r + 2.5f, center = c, style = Stroke(2f))
            }
        }
    }

    /** Colour ramp for a day's progress: dawn violet -> sunrise orange -> gold. */
    fun progressColor(p: Float): Color {
        val stop = SkyPalette.at(0.25f + 0.75f * p.coerceIn(0f, 1f))
        return if (p >= 0.999f) Gold else lerp(stop.horizon, stop.sun, 0.5f)
    }

    /** Mini sunrise disc for headers. */
    fun miniSun(sizePx: Int, progress: Float, date: LocalDate): Bitmap = bitmap(sizePx, sizePx) {
        val r = size.minDimension / 2
        withTransform({ clipPath(androidx.compose.ui.graphics.Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset.Zero, size)) }) }) {
            drawSky(progress, MoonPhase.phase(date), stars = SkyAssets.fewStars, clouds = SkyAssets.clouds.take(2), hillFraction = 0.28f, detail = false)
        }
        drawCircle(Color.White.copy(alpha = 0.25f), radius = r - 0.5f, center = Offset(r, r), style = Stroke(1f))
    }

    // --- internals ---

    private fun DrawScope.roundedClip(radiusPx: Float, block: DrawScope.() -> Unit) {
        val path = androidx.compose.ui.graphics.Path().apply {
            addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, CornerRadius(radiusPx)))
        }
        withTransform({ clipPath(path) }) { block() }
    }

    /** Draws a Material [ImageVector] by walking its path nodes; no composition needed. */
    fun DrawScope.drawIcon(vector: ImageVector, sizePx: Float, tint: Color, topLeft: Offset) {
        val sx = sizePx / vector.viewportWidth
        val sy = sizePx / vector.viewportHeight
        withTransform({
            translate(topLeft.x, topLeft.y)
            scale(sx, sy, Offset.Zero)
        }) { drawGroup(vector.root, tint) }
    }

    private fun DrawScope.drawGroup(group: VectorGroup, tint: Color) {
        withTransform({
            translate(group.translationX, group.translationY)
            if (group.rotation != 0f) rotate(group.rotation, Offset(group.pivotX, group.pivotY))
            if (group.scaleX != 1f || group.scaleY != 1f) scale(group.scaleX, group.scaleY, Offset(group.pivotX, group.pivotY))
        }) {
            for (node in group) {
                when (node) {
                    is VectorGroup -> drawGroup(node, tint)
                    is VectorPath -> {
                        val path = PathParser().addPathNodes(node.pathData).toPath()
                        path.fillType = node.pathFillType
                        if (node.fill != null) drawPath(path, tint, alpha = node.fillAlpha)
                        if (node.stroke != null && node.strokeLineWidth > 0f) {
                            drawPath(path, tint, alpha = node.strokeAlpha, style = Stroke(node.strokeLineWidth, cap = node.strokeLineCap, join = node.strokeLineJoin))
                        }
                    }
                }
            }
        }
    }

    fun dp(context: android.content.Context, dp: Float): Int = (dp * context.resources.displayMetrics.density).roundToInt().coerceAtLeast(1)
}
