package com.repdayrise.app.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.repdayrise.app.ui.components.SkyAssets
import com.repdayrise.app.ui.components.SystemBars
import com.repdayrise.app.ui.components.drawSky
import com.repdayrise.app.ui.theme.Gold
import kotlinx.coroutines.launch
import java.time.LocalDate

private data class Page(val title: String, val body: String)

private val pages = listOf(
    Page("Rise with your habits", "Every habit you complete lifts the sun a little higher. Start the day in darkness and end it in full daylight."),
    Page("Track what matters", "Simple checks, measurable goals like glasses of water, or timers for focus and meditation. Daily, weekly or monthly."),
    Page("Watch yourself grow", "Streaks, calendars and statistics show your progress. Widgets and reminders keep the sun rising, even on busy days."),
)

@Composable
fun OnboardingScreen(onFinish: (addStarterHabits: Boolean) -> Unit) {
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    var starter by remember { mutableStateOf(true) }
    SystemBars(lightStatusIcons = true)

    val progress = ((pager.currentPage + pager.currentPageOffsetFraction) / (pages.size - 1)).coerceIn(0f, 1f)
    val moon = remember { com.repdayrise.app.domain.MoonPhase.phase(LocalDate.now()) }

    Box(Modifier.fillMaxSize().background(Color(0xFF05060F))) {
        Canvas(Modifier.fillMaxSize()) { drawSky(progress * 0.98f + 0.02f, moon, stars = SkyAssets.stars, hillFraction = 0.32f, detail = false) }
        HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { }
        Column(Modifier.fillMaxSize().navigationBarsPadding().padding(horizontal = 28.dp, vertical = 24.dp)) {
            Spacer(Modifier.weight(1f))
            AnimatedContent(
                targetState = pager.currentPage,
                transitionSpec = {
                    val forward = targetState > initialState
                    (slideInHorizontally { if (forward) it / 3 else -it / 3 } + fadeIn()) togetherWith (slideOutHorizontally { if (forward) -it / 3 else it / 3 } + fadeOut())
                },
                label = "page",
            ) { index ->
                Column {
                    Text(pages[index].title, style = MaterialTheme.typography.displaySmall, color = Color.White)
                    Spacer(Modifier.height(12.dp))
                    Text(pages[index].body, style = MaterialTheme.typography.bodyLarge, color = Color.White.copy(alpha = 0.85f))
                }
            }
            Spacer(Modifier.height(28.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                repeat(pages.size) { i ->
                    val w by animateDpAsState(if (i == pager.currentPage) 22.dp else 8.dp, label = "dot")
                    Box(Modifier.padding(end = 6.dp).height(8.dp).width(w).clip(CircleShape).background(if (i == pager.currentPage) Gold else Color.White.copy(alpha = 0.35f)))
                }
            }
            Spacer(Modifier.height(20.dp))
            if (pager.currentPage == pages.lastIndex) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Checkbox(checked = starter, onCheckedChange = { starter = it })
                    Text("Add three starter habits to get going", color = Color.White, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(8.dp))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (pager.currentPage < pages.lastIndex) {
                    TextButton(onClick = { onFinish(false) }) { Text("Skip", color = Color.White.copy(alpha = 0.8f)) }
                }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = {
                        if (pager.currentPage < pages.lastIndex) scope.launch { pager.animateScrollToPage(pager.currentPage + 1) }
                        else onFinish(starter)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Gold, contentColor = Color(0xFF3A1A00)),
                    shape = CircleShape,
                ) {
                    Text(if (pager.currentPage < pages.lastIndex) "Continue" else "Start rising", textAlign = TextAlign.Center)
                }
            }
        }
    }
}
