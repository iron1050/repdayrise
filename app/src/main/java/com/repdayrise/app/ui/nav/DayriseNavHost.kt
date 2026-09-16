package com.repdayrise.app.ui.nav

import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import com.repdayrise.app.AppContainer
import com.repdayrise.app.ui.edit.EditHabitScreen
import com.repdayrise.app.ui.edit.EditHabitViewModel
import com.repdayrise.app.ui.habit.HabitDetailScreen
import com.repdayrise.app.ui.habit.HabitDetailViewModel
import com.repdayrise.app.ui.history.HistoryScreen
import com.repdayrise.app.ui.history.HistoryViewModel
import com.repdayrise.app.ui.home.HomeScreen
import com.repdayrise.app.ui.home.HomeViewModel
import com.repdayrise.app.ui.settings.HallOfFameScreen
import com.repdayrise.app.ui.settings.HallOfFameViewModel
import com.repdayrise.app.ui.settings.SettingsScreen
import com.repdayrise.app.ui.settings.SettingsViewModel

@Composable
fun DayriseNavHost(container: AppContainer, pendingHabitId: Long?, pendingHistory: Boolean = false, onPendingConsumed: () -> Unit) {
    val backStack = rememberNavBackStack(HomeRoute)

    LaunchedEffect(pendingHabitId, pendingHistory) {
        if (pendingHabitId != null && pendingHabitId > 0) {
            backStack.add(HabitDetailRoute(pendingHabitId))
            onPendingConsumed()
        } else if (pendingHistory) {
            if (backStack.lastOrNull() != HistoryRoute) backStack.add(HistoryRoute)
            onPendingConsumed()
        }
    }

    fun pop() { if (backStack.size > 1) backStack.removeLastOrNull() }

    NavDisplay(
        backStack = backStack,
        modifier = Modifier,
        onBack = { pop() },
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberViewModelStoreNavEntryDecorator(),
        ),
        transitionSpec = {
            (slideInHorizontally(tween(380)) { it / 5 } + fadeIn(tween(260))) togetherWith
                (slideOutHorizontally(tween(380)) { -it / 8 } + fadeOut(tween(260)))
        },
        popTransitionSpec = {
            (slideInHorizontally(tween(340)) { -it / 8 } + fadeIn(tween(240))) togetherWith
                (slideOutHorizontally(tween(340)) { it / 4 } + fadeOut(tween(240)))
        },
        predictivePopTransitionSpec = { _ ->
            (fadeIn(tween(200)) + scaleIn(tween(200), initialScale = 0.96f)) togetherWith
                (slideOutHorizontally(tween(300)) { it / 3 } + fadeOut(tween(300)) + scaleOut(tween(300), targetScale = 0.9f))
        },
        entryProvider = entryProvider {
            entry<HomeRoute> {
                val vm: HomeViewModel = viewModel { HomeViewModel(container) }
                HomeScreen(
                    viewModel = vm,
                    onOpenHabit = { backStack.add(HabitDetailRoute(it)) },
                    onAddHabit = { backStack.add(EditHabitRoute()) },
                    onOpenHistory = { backStack.add(HistoryRoute) },
                    onOpenSettings = { backStack.add(SettingsRoute) },
                )
            }
            entry<HabitDetailRoute> { route ->
                val vm: HabitDetailViewModel = viewModel(key = "detail-${route.habitId}") { HabitDetailViewModel(container, route.habitId) }
                HabitDetailScreen(viewModel = vm, onBack = { pop() }, onEdit = { backStack.add(EditHabitRoute(habitId = it)) })
            }
            entry<EditHabitRoute> { route ->
                val vm: EditHabitViewModel = viewModel(key = "edit-${route.habitId}") { EditHabitViewModel(container, route.habitId, route.groupId) }
                EditHabitScreen(viewModel = vm, onDone = { pop() })
            }
            entry<HistoryRoute> {
                val vm: HistoryViewModel = viewModel { HistoryViewModel(container) }
                HistoryScreen(viewModel = vm, onBack = { pop() }, onOpenHabit = { backStack.add(HabitDetailRoute(it)) })
            }
            entry<SettingsRoute> {
                val vm: SettingsViewModel = viewModel { SettingsViewModel(container) }
                SettingsScreen(viewModel = vm, onBack = { pop() }, onOpenHallOfFame = { backStack.add(HallOfFameRoute) })
            }
            entry<HallOfFameRoute> {
                val vm: HallOfFameViewModel = viewModel { HallOfFameViewModel(container) }
                HallOfFameScreen(viewModel = vm, onBack = { pop() }, onOpenHabit = { backStack.add(HabitDetailRoute(it)) })
            }
        },
    )
}
