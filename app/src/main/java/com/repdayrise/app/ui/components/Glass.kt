package com.repdayrise.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.repdayrise.app.ui.theme.Indigo
import com.repdayrise.app.ui.theme.LocalIsDark
import com.repdayrise.app.ui.theme.Sunrise
import com.repdayrise.app.ui.theme.SunriseDeep

/* ------------------------------------------------------------------------------------------
 * Backdrop blur ("frosted glass")
 *
 * A [Backdrop] records the content of a source composable into a GraphicsLayer. Any number of
 * consumers can then draw that layer blurred behind themselves with [frosted]. Because the
 * consumer draws a reference to the source's render node, scrolling content under a frosted
 * bar stays live without extra recomposition.
 * ---------------------------------------------------------------------------------------- */

class Backdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var coordinates: LayoutCoordinates? by mutableStateOf(null)
}

@Composable
fun rememberBackdrop(): Backdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { Backdrop(layer) }
}

/** Marks this node as the content that [frosted] consumers blur. */
fun Modifier.backdropSource(backdrop: Backdrop): Modifier = this
    .onGloballyPositioned { backdrop.coordinates = it }
    .drawWithContent {
        backdrop.layer.record { this@drawWithContent.drawContent() }
        drawLayer(backdrop.layer)
    }

/**
 * Draws a blurred copy of [backdrop] behind this node, tinted with [tint], clipped to [shape].
 * Falls back to the plain tint when the source hasn't been laid out yet.
 */
@Composable
fun Modifier.frosted(
    backdrop: Backdrop,
    tint: Color,
    shape: Shape = RoundedCornerShape(0.dp),
    blur: Dp = 28.dp,
    saturationBoost: Boolean = true,
): Modifier {
    val blurLayer = rememberGraphicsLayer()
    var self by remember { mutableStateOf<LayoutCoordinates?>(null) }
    return this
        .onGloballyPositioned { self = it }
        .clip(shape)
        .drawBehind {
            val src = backdrop.coordinates
            val me = self
            if (src != null && me != null && src.isAttached && me.isAttached) {
                val origin = me.localPositionOf(src, Offset.Zero)
                val radius = blur.toPx()
                blurLayer.renderEffect = BlurEffect(radius, radius, TileMode.Clamp)
                blurLayer.record(size = IntSize(src.size.width, src.size.height)) { drawLayer(backdrop.layer) }
                translate(origin.x, origin.y) { drawLayer(blurLayer) }
            }
            drawRect(tint)
            if (saturationBoost) {
                // A whisper of a highlight along the top edge sells the "glass" read.
                drawRect(
                    Brush.verticalGradient(0f to Color.White.copy(alpha = 0.10f), 1f to Color.Transparent),
                    size = Size(size.width, size.height.coerceAtMost(48.dp.toPx())),
                )
            }
        }
}

/** Standard glass tint for bars and cards, tuned per theme. */
@Composable
fun glassTint(alpha: Float = 0.72f): Color = MaterialTheme.colorScheme.background.copy(alpha = alpha)

/** Hairline border used on glass cards. */
@Composable
fun Modifier.glassBorder(shape: Shape, alpha: Float = if (LocalIsDark.current) 0.12f else 0.55f): Modifier =
    border(1.dp, Color.White.copy(alpha = alpha), shape)

/* ------------------------------------------------------------------------------------------
 * Ambient background: base colour plus two soft glows that echo the sunrise palette.
 * ---------------------------------------------------------------------------------------- */

@Composable
fun Modifier.dayriseBackground(accent: Color? = null): Modifier {
    val dark = LocalIsDark.current
    val base = MaterialTheme.colorScheme.background
    val warm = accent ?: Sunrise
    val cool = Indigo
    return drawBehind {
        drawRect(base)
        val w = size.width
        val h = size.height
        val warmAlpha = if (dark) 0.22f else 0.16f
        val coolAlpha = if (dark) 0.30f else 0.10f
        // Warm glow, top right
        drawCircle(
            Brush.radialGradient(
                0f to warm.copy(alpha = warmAlpha), 0.55f to warm.copy(alpha = warmAlpha * 0.35f), 1f to warm.copy(alpha = 0f),
                center = Offset(w * 0.92f, h * 0.02f), radius = w * 0.85f,
            ),
            radius = w * 0.85f, center = Offset(w * 0.92f, h * 0.02f),
        )
        // Cool glow, bottom left
        drawCircle(
            Brush.radialGradient(
                0f to cool.copy(alpha = coolAlpha), 0.6f to cool.copy(alpha = coolAlpha * 0.3f), 1f to cool.copy(alpha = 0f),
                center = Offset(w * 0.05f, h * 0.98f), radius = w * 0.95f,
            ),
            radius = w * 0.95f, center = Offset(w * 0.05f, h * 0.98f),
        )
        if (dark) {
            // Faint ember low on the right so the night isn't flat.
            drawCircle(
                Brush.radialGradient(
                    0f to SunriseDeep.copy(alpha = 0.10f), 1f to SunriseDeep.copy(alpha = 0f),
                    center = Offset(w * 0.85f, h * 0.75f), radius = w * 0.6f,
                ),
                radius = w * 0.6f, center = Offset(w * 0.85f, h * 0.75f),
            )
        }
    }
}

/* ------------------------------------------------------------------------------------------
 * Gradient helpers for accents.
 * ---------------------------------------------------------------------------------------- */

/** A gentle top-left-to-bottom-right gradient that lifts a flat habit colour. */
fun accentGradient(color: Color): Brush = Brush.linearGradient(
    listOf(lerp(color, Color.White, 0.22f), color, lerp(color, Color.Black, 0.10f)),
)

/** Sunrise pill gradient for primary actions. */
val SunrisePill: Brush = Brush.horizontalGradient(listOf(Color(0xFFFF9A4D), Sunrise, Color(0xFFE85D8A)))

/** Draws a soft glow of [color] in the top-left corner; used inside cards. */
fun DrawScope.drawCornerGlow(color: Color, alpha: Float = 0.10f) {
    val r = size.maxDimension * 0.9f
    drawCircle(
        Brush.radialGradient(0f to color.copy(alpha = alpha), 1f to color.copy(alpha = 0f), center = Offset(0f, 0f), radius = r),
        radius = r, center = Offset(0f, 0f),
    )
}

/** Card surface with a subtle inner top highlight and optional corner glow. */
@Composable
fun GlowCard(
    modifier: Modifier = Modifier,
    shape: Shape = MaterialTheme.shapes.large,
    color: Color = MaterialTheme.colorScheme.surfaceContainer,
    glow: Color? = null,
    content: @Composable () -> Unit,
) {
    val dark = LocalIsDark.current
    Box(
        modifier
            .clip(shape)
            .background(color)
            .drawBehind {
                if (glow != null) drawCornerGlow(glow, if (dark) 0.16f else 0.10f)
                drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = if (dark) 0.04f else 0.5f), 0.35f to Color.Transparent))
            }
            .glassBorder(shape, alpha = if (dark) 0.06f else 0.7f),
    ) { content() }
}

/** Simple wrapper so callers don't need the layout import for a full-size glass box. */
@Composable
fun GlassBox(backdrop: Backdrop, shape: Shape, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.frosted(backdrop, glassTint(), shape).glassBorder(shape)) { Box(Modifier.fillMaxSize()) { content() } }
}
