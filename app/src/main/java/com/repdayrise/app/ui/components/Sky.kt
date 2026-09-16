package com.repdayrise.app.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.lerp
import com.repdayrise.app.domain.MoonPhase
import java.time.LocalDate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Colour keyframes for the living sky, from deep night (0) to full daylight (1). */
object SkyPalette {
    data class Stop(val top: Color, val mid: Color, val horizon: Color, val hillBack: Color, val hillFront: Color, val sun: Color)

    private val stops: List<Pair<Float, Stop>> = listOf(
        0.00f to Stop(Color(0xFF05060F), Color(0xFF0F1633), Color(0xFF1B2450), Color(0xFF0E1230), Color(0xFF06081A), Color(0xFFFF7A3D)),
        0.25f to Stop(Color(0xFF12173A), Color(0xFF3A2F6B), Color(0xFFB14E63), Color(0xFF241F4E), Color(0xFF0E1130), Color(0xFFFF8A3D)),
        0.50f to Stop(Color(0xFF223066), Color(0xFFC2587A), Color(0xFFF79B5E), Color(0xFF3A2C5C), Color(0xFF1C1740), Color(0xFFFFA24A)),
        0.75f to Stop(Color(0xFF3F7BC4), Color(0xFFF2A566), Color(0xFFFFD08A), Color(0xFF4B4C7A), Color(0xFF2B2A55), Color(0xFFFFC55C)),
        1.00f to Stop(Color(0xFF3B8FE0), Color(0xFF8CC7F5), Color(0xFFE2F1FF), Color(0xFF6C8FBF), Color(0xFF4A6CA0), Color(0xFFFFE27A)),
    )

    fun at(progress: Float): Stop {
        val p = progress.coerceIn(0f, 1f)
        for (i in 0 until stops.lastIndex) {
            val (p0, s0) = stops[i]
            val (p1, s1) = stops[i + 1]
            if (p <= p1) {
                val t = ((p - p0) / (p1 - p0)).coerceIn(0f, 1f)
                return Stop(
                    lerp(s0.top, s1.top, t), lerp(s0.mid, s1.mid, t), lerp(s0.horizon, s1.horizon, t),
                    lerp(s0.hillBack, s1.hillBack, t), lerp(s0.hillFront, s1.hillFront, t), lerp(s0.sun, s1.sun, t),
                )
            }
        }
        return stops.last().second
    }
}

class Star(val x: Float, val y: Float, val size: Float, val phase: Float, val speed: Float)
class Cloud(val x: Float, val y: Float, val scale: Float, val speed: Float, val alpha: Float)

object SkyAssets {
    val stars: List<Star> by lazy {
        val r = Random(20250925)
        List(90) { Star(r.nextFloat(), r.nextFloat() * 0.72f, 0.5f + r.nextFloat() * 1.4f, r.nextFloat() * 6.28f, 0.6f + r.nextFloat() * 1.6f) }
    }
    val fewStars: List<Star> by lazy { stars.take(28) }
    val clouds: List<Cloud> by lazy {
        listOf(
            Cloud(0.15f, 0.22f, 1.0f, 0.010f, 0.85f),
            Cloud(0.62f, 0.14f, 0.75f, 0.014f, 0.7f),
            Cloud(0.88f, 0.34f, 0.9f, 0.008f, 0.6f),
            Cloud(0.40f, 0.42f, 0.6f, 0.012f, 0.5f),
        )
    }
}

private fun easeOutCubic(t: Float): Float { val u = 1f - t; return 1f - u * u * u }

/**
 * Draws the living sky. [progress] 0..1 raises the sun; [time] (seconds) drives twinkle and cloud drift.
 * [hillFraction] is the share of the height taken by the hills at the bottom.
 */
fun DrawScope.drawSky(
    progress: Float,
    moonPhase: Double,
    time: Float = 0f,
    stars: List<Star> = SkyAssets.stars,
    clouds: List<Cloud> = SkyAssets.clouds,
    hillFraction: Float = 0.24f,
    detail: Boolean = true,
    sizeOverride: Size? = null,
) {
    val p = progress.coerceIn(0f, 1f)
    val w = sizeOverride?.width ?: size.width
    val h = sizeOverride?.height ?: size.height
    val stop = SkyPalette.at(p)

    // Sky gradient
    drawRect(Brush.verticalGradient(0f to stop.top, 0.62f to stop.mid, 1f to stop.horizon, startY = 0f, endY = h))

    // Stars
    val starAlpha = (1f - p / 0.55f).coerceIn(0f, 1f)
    if (starAlpha > 0.01f) {
        val density = min(w, h) / 200f
        for (s in stars) {
            val twinkle = if (detail) 0.55f + 0.45f * sin(time * s.speed + s.phase) else 0.85f
            drawCircle(Color.White.copy(alpha = starAlpha * twinkle), radius = s.size * density, center = Offset(s.x * w, s.y * h))
        }
    }

    // Moon (sets as the sun rises)
    val moonAlpha = (1f - p / 0.45f).coerceIn(0f, 1f)
    if (moonAlpha > 0.01f) {
        val r = min(w, h) * 0.075f
        val cx = w * 0.78f
        val cy = h * 0.22f + p * h * 0.6f
        drawMoon(moonPhase, Offset(cx, cy), r, moonAlpha)
    }

    // Sun
    val horizonY = h * (1f - hillFraction)
    val sunR = min(w, h) * 0.12f
    val sunStart = horizonY + sunR * 1.15f
    val sunEnd = h * 0.24f
    val sunY = sunStart + (sunEnd - sunStart) * easeOutCubic(p)
    val sunX = w * 0.5f
    val sunColor = stop.sun
    val glowAlpha = 0.28f + 0.3f * (1f - p)
    drawCircle(
        Brush.radialGradient(
            0f to sunColor.copy(alpha = glowAlpha), 0.45f to sunColor.copy(alpha = glowAlpha * 0.35f), 1f to sunColor.copy(alpha = 0f),
            center = Offset(sunX, sunY), radius = sunR * 3.4f,
        ),
        radius = sunR * 3.4f, center = Offset(sunX, sunY),
    )
    drawCircle(
        Brush.verticalGradient(
            0f to lerp(sunColor, Color.White, 0.45f), 1f to sunColor,
            startY = sunY - sunR, endY = sunY + sunR,
        ),
        radius = sunR, center = Offset(sunX, sunY),
    )

    // Horizon haze
    drawRect(
        Brush.verticalGradient(0f to stop.horizon.copy(alpha = 0f), 1f to stop.horizon.copy(alpha = 0.55f), startY = horizonY - h * 0.25f, endY = horizonY),
        topLeft = Offset(0f, horizonY - h * 0.25f), size = Size(w, h * 0.25f),
    )

    // Clouds
    val cloudAlpha = ((p - 0.5f) / 0.5f).coerceIn(0f, 1f)
    if (cloudAlpha > 0.01f) {
        for (c in clouds) {
            val span = w * 1.4f
            val drift = if (detail) (time * c.speed * w) % span else 0f
            val cx = ((c.x * w + drift) % span) - w * 0.2f
            drawCloud(Offset(cx, c.y * h), min(w, h) * 0.16f * c.scale, Color.White.copy(alpha = cloudAlpha * c.alpha))
        }
    }

    // Hills
    val back = Path().apply {
        moveTo(0f, horizonY + h * 0.02f)
        cubicTo(w * 0.18f, horizonY - h * 0.07f, w * 0.34f, horizonY - h * 0.05f, w * 0.52f, horizonY + h * 0.01f)
        cubicTo(w * 0.7f, horizonY + h * 0.06f, w * 0.86f, horizonY - h * 0.02f, w, horizonY - h * 0.05f)
        lineTo(w, h); lineTo(0f, h); close()
    }
    drawPath(back, stop.hillBack)
    val front = Path().apply {
        val y = horizonY + h * 0.09f
        moveTo(0f, y + h * 0.03f)
        cubicTo(w * 0.22f, y - h * 0.05f, w * 0.4f, y - h * 0.06f, w * 0.6f, y)
        cubicTo(w * 0.78f, y + h * 0.05f, w * 0.9f, y + h * 0.02f, w, y - h * 0.02f)
        lineTo(w, h); lineTo(0f, h); close()
    }
    drawPath(front, stop.hillFront)
}

private fun DrawScope.drawCloud(center: Offset, r: Float, color: Color) {
    drawCircle(color, r * 0.55f, center + Offset(-r * 0.9f, r * 0.15f))
    drawCircle(color, r * 0.75f, center + Offset(-r * 0.3f, -r * 0.1f))
    drawCircle(color, r * 0.65f, center + Offset(r * 0.35f, 0f))
    drawCircle(color, r * 0.5f, center + Offset(r * 0.95f, r * 0.2f))
    drawRect(color, topLeft = center + Offset(-r * 0.9f, r * 0.05f), size = Size(r * 1.85f, r * 0.6f))
}

/** Draws a moon disc with the correct lit portion for [phase] (0 new, 0.5 full). */
fun DrawScope.drawMoon(phase: Double, center: Offset, r: Float, alpha: Float) {
    val light = Color(0xFFF5F0D8).copy(alpha = alpha)
    val dark = Color(0xFF1B2040).copy(alpha = alpha * 0.9f)
    drawCircle(
        Brush.radialGradient(
            0f to Color(0xFFF5F0D8).copy(alpha = alpha * 0.22f), 0.5f to Color(0xFFF5F0D8).copy(alpha = alpha * 0.08f), 1f to Color(0xFFF5F0D8).copy(alpha = 0f),
            center = center, radius = r * 2.4f,
        ),
        radius = r * 2.4f, center = center,
    )
    drawCircle(dark, r, center)
    val waxing = phase < 0.5
    val c = cos(2 * PI * phase).toFloat() // +1 new, 0 quarter, -1 full
    val litPath = Path().apply {
        val circle = Rect(center.x - r, center.y - r, center.x + r, center.y + r)
        val tw = r * abs(c)
        val term = Rect(center.x - tw, center.y - r, center.x + tw, center.y + r)
        moveTo(center.x, center.y - r)
        arcTo(circle, -90f, 180f, false)
        // Terminator: an ellipse bulging toward the lit side (crescent) or away from it (gibbous).
        // At a quarter moon it degenerates to a straight line, which close() provides.
        if (tw >= 0.5f) arcTo(term, 90f, if (c > 0) -180f else 180f, false)
        close()
    }
    if (phase < 0.02 || phase > 0.98) return
    if (waxing) {
        drawPath(litPath, light)
    } else {
        scale(scaleX = -1f, scaleY = 1f, pivot = center) { drawPath(litPath, light) }
    }
}

/** Animated sky inputs shared by the hero and any glass surfaces that echo it. */
class SkyState(val progress: Float, val time: Float, val moon: Double)

@Composable
fun rememberSkyState(progress: Float, date: LocalDate): SkyState {
    val animated by animateFloatAsState(progress, tween(1400, easing = FastOutSlowInEasing), label = "sky")
    val transition = rememberInfiniteTransition(label = "skyTime")
    val time by transition.animateFloat(
        0f, 3600f, infiniteRepeatable(tween(3_600_000, easing = LinearEasing)), label = "t",
    )
    val moon = remember(date) { MoonPhase.phase(date) }
    return SkyState(animated, time, moon)
}

/**
 * The animated living sky used on the dashboard. [progress] animates smoothly when it changes.
 */
@Composable
fun SkyScene(
    progress: Float,
    date: LocalDate,
    modifier: Modifier = Modifier,
    hillFraction: Float = 0.24f,
    content: @Composable () -> Unit = {},
) {
    val state = rememberSkyState(progress, date)
    SkyScene(state, modifier, hillFraction, content)
}

@Composable
fun SkyScene(
    state: SkyState,
    modifier: Modifier = Modifier,
    hillFraction: Float = 0.24f,
    content: @Composable () -> Unit = {},
) {
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            drawSky(state.progress, state.moon, state.time, hillFraction = hillFraction)
            // Soft scrim at the top keeps the date and icons legible against a bright noon sky.
            drawRect(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.22f), 1f to Color.Transparent, endY = size.height * 0.35f))
        }
        content()
    }
}

/** A small static sunrise, e.g. for calendar cells. */
@Composable
fun MiniSunrise(progress: Float, date: LocalDate, modifier: Modifier = Modifier) {
    val moon = remember(date) { MoonPhase.phase(date) }
    val animated by animateFloatAsState(progress, tween(600, easing = FastOutSlowInEasing), label = "mini")
    Canvas(modifier) {
        drawSky(animated, moon, stars = SkyAssets.fewStars, clouds = SkyAssets.clouds.take(2), hillFraction = 0.28f, detail = false)
    }
}
