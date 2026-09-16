package com.repdayrise.app.ui.theme

import androidx.compose.ui.graphics.Color

// Brand
val Sunrise = Color(0xFFF2803E)
val SunriseDeep = Color(0xFFD9642A)
val SunriseSoft = Color(0xFFFFD9BF)
val Gold = Color(0xFFFFC14D)
val Night = Color(0xFF0B1026)
val NightSurface = Color(0xFF141A33)
val NightSurfaceHigh = Color(0xFF1C2344)
val NightSurfaceHighest = Color(0xFF262E52)
val Indigo = Color(0xFF3A3F8F)
val IndigoSoft = Color(0xFFDCDDFF)

val Cream = Color(0xFFF7F4EE)
val CreamSurface = Color(0xFFFFFFFF)
val CreamSurfaceHigh = Color(0xFFF1EDE5)
val CreamSurfaceHighest = Color(0xFFE9E4DA)
val Ink = Color(0xFF1A1B2E)
val InkMuted = Color(0xFF5E6078)
val InkFaint = Color(0xFF9EA0B3)

val DarkText = Color(0xFFF2F1F8)
val DarkTextMuted = Color(0xFFA9ACC7)
val DarkTextFaint = Color(0xFF6F7394)

/** Palette used for habits. */
object HabitColors {
    val palette: List<Color> = listOf(
        Color(0xFFFF6B6B), // coral
        Color(0xFFFF8C42), // orange
        Color(0xFFFFB020), // amber
        Color(0xFFF2D14B), // yellow
        Color(0xFF4CC28E), // green
        Color(0xFF2BB5B8), // teal
        Color(0xFF4FA3E3), // sky
        Color(0xFF5B7CFA), // blue
        Color(0xFF7C6CF0), // indigo
        Color(0xFFA86EF5), // purple
        Color(0xFFF06AA8), // pink
        Color(0xFFE8536F), // rose
    )

    fun of(index: Int): Color = palette[((index % palette.size) + palette.size) % palette.size]
}
