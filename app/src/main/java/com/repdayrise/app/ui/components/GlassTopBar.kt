package com.repdayrise.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import com.repdayrise.app.ui.theme.LocalIsDark

/**
 * A top app bar that blurs whatever scrolls beneath it. Pair with [backdropSource] on the
 * scrolling content and let that content extend under the bar (use the Scaffold padding as
 * inner content padding rather than outer padding).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GlassTopBar(
    backdrop: Backdrop,
    title: @Composable () -> Unit = {},
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    val dark = LocalIsDark.current
    Box(
        Modifier
            .fillMaxWidth()
            .frosted(backdrop, glassTint(0.66f))
            .drawBehind {
                drawRect(
                    (if (dark) Color.White else Color.Black).copy(alpha = if (dark) 0.06f else 0.05f),
                    topLeft = Offset(0f, size.height - 1f), size = Size(size.width, 1f),
                )
            },
    ) {
        TopAppBar(
            title = title,
            navigationIcon = navigationIcon,
            actions = actions,
            colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent),
        )
    }
}
