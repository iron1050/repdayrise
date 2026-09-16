package com.repdayrise.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.repdayrise.app.data.model.ThemeMode

private val LightColors = lightColorScheme(
    primary = Sunrise,
    onPrimary = Color.White,
    primaryContainer = SunriseSoft,
    onPrimaryContainer = Color(0xFF5A2A0A),
    secondary = Indigo,
    onSecondary = Color.White,
    secondaryContainer = IndigoSoft,
    onSecondaryContainer = Color(0xFF1B1D5C),
    tertiary = Color(0xFFB98A00),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFE9A8),
    onTertiaryContainer = Color(0xFF3F2E00),
    background = Cream,
    onBackground = Ink,
    surface = Cream,
    onSurface = Ink,
    surfaceVariant = CreamSurfaceHigh,
    onSurfaceVariant = InkMuted,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFBF9F5),
    surfaceContainer = CreamSurface,
    surfaceContainerHigh = CreamSurfaceHigh,
    surfaceContainerHighest = CreamSurfaceHighest,
    outline = Color(0xFFC9C5BB),
    outlineVariant = Color(0xFFE3DFD6),
    error = Color(0xFFD64545),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    inverseSurface = Ink,
    inverseOnSurface = Cream,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFFB067),
    onPrimary = Color(0xFF3A1A00),
    primaryContainer = Color(0xFF6B3A12),
    onPrimaryContainer = Color(0xFFFFDCC2),
    secondary = Color(0xFFB9BCFF),
    onSecondary = Color(0xFF1B1D5C),
    secondaryContainer = Color(0xFF2E3277),
    onSecondaryContainer = IndigoSoft,
    tertiary = Gold,
    onTertiary = Color(0xFF3F2E00),
    tertiaryContainer = Color(0xFF5C4400),
    onTertiaryContainer = Color(0xFFFFE9A8),
    background = Night,
    onBackground = DarkText,
    surface = Night,
    onSurface = DarkText,
    surfaceVariant = NightSurfaceHigh,
    onSurfaceVariant = DarkTextMuted,
    surfaceContainerLowest = Color(0xFF070A18),
    surfaceContainerLow = Color(0xFF10152B),
    surfaceContainer = NightSurface,
    surfaceContainerHigh = NightSurfaceHigh,
    surfaceContainerHighest = NightSurfaceHighest,
    outline = Color(0xFF4A5075),
    outlineVariant = Color(0xFF2C3252),
    error = Color(0xFFFF8A80),
    onError = Color(0xFF5A0000),
    errorContainer = Color(0xFF7A1F1F),
    onErrorContainer = Color(0xFFFFDAD6),
    inverseSurface = DarkText,
    inverseOnSurface = Night,
)

val DayriseShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

val LocalIsDark = staticCompositionLocalOf { false }

@Composable
fun DayriseTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(LocalIsDark provides dark) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = DayriseTypography,
            shapes = DayriseShapes,
            content = content,
        )
    }
}
