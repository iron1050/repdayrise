package com.repdayrise.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.repdayrise.app.data.model.DaysMask
import com.repdayrise.app.data.model.Habit
import com.repdayrise.app.data.model.HabitType
import com.repdayrise.app.ui.nav.DayriseNavHost
import com.repdayrise.app.ui.onboarding.OnboardingScreen
import com.repdayrise.app.ui.theme.DayriseTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var pendingHabitId by mutableStateOf<Long?>(null)
    private var pendingHistory by mutableStateOf(false)
    private var pendingJoinCode by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingHabitId = intent?.getLongExtra(EXTRA_HABIT_ID, -1L)?.takeIf { it > 0 }
        pendingHistory = intent?.getBooleanExtra(EXTRA_OPEN_HISTORY, false) == true
        // Only on a fresh launch: after a rotation the saved intent would replay the invite.
        if (savedInstanceState == null) pendingJoinCode = joinCodeOf(intent)
        val container = (application as DayriseApp).container
        var ready = false
        splash.setKeepOnScreenCondition { !ready }

        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = null)
            val s = settings
            if (s != null) ready = true
            DayriseTheme(themeMode = s?.themeMode ?: com.repdayrise.app.data.model.ThemeMode.SYSTEM, dynamicColor = s?.dynamicColor ?: false) {
                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    if (s != null) {
                        Crossfade(targetState = s.onboardingDone, label = "root") { onboarded ->
                            if (!onboarded) {
                                OnboardingScreen(onFinish = { starter ->
                                    lifecycleScope.launch {
                                        if (starter) addStarterHabits(container)
                                        container.settings.setOnboardingDone(true)
                                    }
                                })
                            } else {
                                DayriseNavHost(container = container, pendingHabitId = pendingHabitId, pendingHistory = pendingHistory, pendingJoinCode = pendingJoinCode, onPendingConsumed = { pendingHabitId = null; pendingHistory = false; pendingJoinCode = null })
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingHabitId = intent.getLongExtra(EXTRA_HABIT_ID, -1L).takeIf { it > 0 }
        pendingHistory = intent.getBooleanExtra(EXTRA_OPEN_HISTORY, false)
        pendingJoinCode = joinCodeOf(intent)
    }

    /** Invite links look like dayrise://join/ABCDEFGHJK. */
    private fun joinCodeOf(intent: Intent?): String? {
        val uri = intent?.data ?: return null
        if (intent.action != Intent.ACTION_VIEW || uri.scheme != "dayrise" || uri.host != "join") return null
        return uri.lastPathSegment?.filter { it.isLetterOrDigit() }?.take(16)?.takeIf { it.isNotEmpty() }
    }

    private suspend fun addStarterHabits(container: AppContainer) {
        val repo = container.repository
        if (repo.getAllHabitsOnce().isNotEmpty()) return
        repo.saveHabit(Habit(name = "Morning walk", icon = "walk", colorIndex = 4, type = HabitType.TIMER, goal = 20.0, unit = "min"))
        repo.saveHabit(Habit(name = "Drink water", icon = "water", colorIndex = 6, type = HabitType.COUNT, goal = 8.0, unit = "glasses"))
        repo.saveHabit(Habit(name = "Read", icon = "book", colorIndex = 8, type = HabitType.CHECK, daysMask = DaysMask.ALL))
    }

    companion object {
        const val EXTRA_HABIT_ID = "extra_habit_id"
        const val EXTRA_OPEN_HISTORY = "extra_open_history"
    }
}
