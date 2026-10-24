package com.repdayrise.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate

/**
 * A secondary page in the same visual language as the Progress dashboard: a living sunrise sky with the title over it,
 * and a frosted glass panel that rises over the hills and holds the content.
 */
@Composable
fun SkyPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    progress: Float = 0.62f,
    actions: @Composable RowScope.() -> Unit = {},
    snackbarHost: @Composable () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    SystemBars(lightStatusIcons = true)
    val backdrop = rememberBackdrop()
    val sky = rememberSkyState(progress, LocalDate.now())
    val statusPadding = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val heroHeight = statusPadding + 188.dp
    val overlap = 28.dp
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    val shape = RoundedCornerShape(topStart = 32.dp, topEnd = 32.dp)

    Scaffold(
        modifier = modifier.dayriseBackground(),
        containerColor = Color.Transparent,
        contentColor = MaterialTheme.colorScheme.onBackground,
        snackbarHost = snackbarHost,
    ) { _ ->
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Box(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(heroHeight)) {
                    Box(Modifier.fillMaxSize().backdropSource(backdrop)) {
                        SkyScene(state = sky, hillFraction = 0.14f, modifier = Modifier.fillMaxSize())
                    }
                    Column(Modifier.fillMaxSize().padding(top = statusPadding)) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = onBack, colors = IconButtonDefaults.iconButtonColors(contentColor = Color.White)) {
                                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                            }
                            Spacer(Modifier.weight(1f))
                            Row(verticalAlignment = Alignment.CenterVertically) { actions() }
                        }
                        Spacer(Modifier.weight(1f))
                        Column(Modifier.padding(start = 24.dp, end = 24.dp, bottom = overlap + 14.dp)) {
                            Text(title, style = MaterialTheme.typography.headlineLarge, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.88f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Column(
                    Modifier
                        .padding(top = heroHeight - overlap)
                        .fillMaxWidth()
                        .heightIn(min = screenHeight - heroHeight + overlap)
                        .frosted(backdrop, glassTint(0.9f), shape)
                        .glassBorder(shape, alpha = 0.10f)
                        .padding(horizontal = 16.dp)
                        .padding(top = 18.dp, bottom = 32.dp + navPadding),
                    content = content,
                )
            }
        }
    }
}

/** Small caps-style label that sits above a group of rows. */
@Composable
fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 6.dp, top = 10.dp, bottom = 8.dp),
    )
}

/** A settings-style row: a tinted icon bead, title and subtitle, and a trailing slot (chevron by default). */
@Composable
fun BeadRow(
    title: String,
    subtitle: String?,
    icon: ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    },
) {
    Row(
        modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, accent, size = 40.dp, iconSize = 21.dp, filled = true)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(10.dp))
        trailing()
    }
}

@Composable
fun BeadToggleRow(title: String, subtitle: String, icon: ImageVector, accent: Color, checked: Boolean, onChange: (Boolean) -> Unit) {
    BeadRow(title, subtitle, icon, accent, onClick = { onChange(!checked) }, trailing = { Switch(checked = checked, onCheckedChange = onChange) })
}
