package com.repdayrise.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.repdayrise.app.data.model.AppIcon
import com.repdayrise.app.data.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

data class Settings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val weekStartsMonday: Boolean = true,
    val onboardingDone: Boolean = false,
    val appIcon: AppIcon = AppIcon.SUNRISE,
    val completedToBottom: Boolean = true,
    val haptics: Boolean = true,
    val healthEnabled: Boolean = false,
)

class SettingsRepository(private val context: Context) {
    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val DYNAMIC = booleanPreferencesKey("dynamic_color")
        val WEEK_MONDAY = booleanPreferencesKey("week_starts_monday")
        val ONBOARDED = booleanPreferencesKey("onboarding_done")
        val ICON = stringPreferencesKey("app_icon")
        val COMPLETED_BOTTOM = booleanPreferencesKey("completed_to_bottom")
        val HAPTICS = booleanPreferencesKey("haptics")
        val HEALTH = booleanPreferencesKey("health_enabled")
    }

    val settings: Flow<Settings> = context.settingsStore.data.map { p ->
        Settings(
            themeMode = p[Keys.THEME]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM,
            dynamicColor = p[Keys.DYNAMIC] ?: false,
            weekStartsMonday = p[Keys.WEEK_MONDAY] ?: true,
            onboardingDone = p[Keys.ONBOARDED] ?: false,
            appIcon = p[Keys.ICON]?.let { runCatching { AppIcon.valueOf(it) }.getOrNull() } ?: AppIcon.SUNRISE,
            completedToBottom = p[Keys.COMPLETED_BOTTOM] ?: true,
            haptics = p[Keys.HAPTICS] ?: true,
            healthEnabled = p[Keys.HEALTH] ?: false,
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setThemeMode(mode: ThemeMode) = context.settingsStore.edit { it[Keys.THEME] = mode.name }
    suspend fun setDynamicColor(enabled: Boolean) = context.settingsStore.edit { it[Keys.DYNAMIC] = enabled }
    suspend fun setWeekStartsMonday(monday: Boolean) = context.settingsStore.edit { it[Keys.WEEK_MONDAY] = monday }
    suspend fun setOnboardingDone(done: Boolean) = context.settingsStore.edit { it[Keys.ONBOARDED] = done }
    suspend fun setAppIcon(icon: AppIcon) = context.settingsStore.edit { it[Keys.ICON] = icon.name }
    suspend fun setCompletedToBottom(enabled: Boolean) = context.settingsStore.edit { it[Keys.COMPLETED_BOTTOM] = enabled }
    suspend fun setHaptics(enabled: Boolean) = context.settingsStore.edit { it[Keys.HAPTICS] = enabled }
    suspend fun setHealthEnabled(enabled: Boolean) = context.settingsStore.edit { it[Keys.HEALTH] = enabled }
}
