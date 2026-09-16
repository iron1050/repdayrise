package com.repdayrise.app.ui.components

import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.repdayrise.app.ui.theme.LocalIsDark

/**
 * Sets system bar icon colours for the current screen. [lightStatusIcons] = white status icons
 * (for dark backdrops such as the sky). Navigation bar icons always follow the app theme.
 */
@Composable
fun SystemBars(lightStatusIcons: Boolean = LocalIsDark.current) {
    val view = LocalView.current
    val isDark = LocalIsDark.current
    DisposableEffect(lightStatusIcons, isDark) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = !lightStatusIcons
        controller?.isAppearanceLightNavigationBars = !isDark
        onDispose { }
    }
}
