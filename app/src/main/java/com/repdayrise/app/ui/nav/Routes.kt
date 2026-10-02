package com.repdayrise.app.ui.nav

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object HomeRoute : NavKey
@Serializable data class HabitDetailRoute(val habitId: Long) : NavKey
@Serializable data class EditHabitRoute(val habitId: Long? = null, val groupId: Long? = null) : NavKey
@Serializable data object HistoryRoute : NavKey
@Serializable data object SettingsRoute : NavKey
@Serializable data object HallOfFameRoute : NavKey
@Serializable data class PartnersRoute(val joinCode: String? = null) : NavKey
@Serializable data class PartnerDetailRoute(val shareId: String) : NavKey
