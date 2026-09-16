package com.repdayrise.app

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import com.repdayrise.app.data.DataChangeListener
import com.repdayrise.app.data.HabitRepository
import com.repdayrise.app.data.SettingsRepository
import com.repdayrise.app.data.db.DayriseDatabase
import com.repdayrise.app.data.health.HealthConnectManager
import com.repdayrise.app.data.health.HealthSync
import com.repdayrise.app.data.model.AppIcon
import com.repdayrise.app.domain.HabitLogic
import com.repdayrise.app.notifications.Channels
import com.repdayrise.app.notifications.ReminderScheduler
import com.repdayrise.app.widget.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.DayOfWeek

class DayriseApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        Channels.ensure(this)
    }
}

class AppContainer(val context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db: DayriseDatabase = DayriseDatabase.create(context)
    val settings = SettingsRepository(context)
    val repository = HabitRepository(db, appScope)
    val health = HealthConnectManager(context)
    val healthSync = HealthSync(repository, health)
    val reminderScheduler = ReminderScheduler(context)
    val widgetUpdater = WidgetUpdater(context)

    @Volatile private var weekStartsMonday = true

    init {
        repository.listener = object : DataChangeListener {
            override fun onHabitsChanged() {
                appScope.launch {
                    reminderScheduler.scheduleAll(repository.getAllHabitsOnce())
                    widgetUpdater.updateAll()
                }
            }

            override fun onEntriesChanged() {
                appScope.launch { widgetUpdater.updateAll() }
            }
        }
        appScope.launch {
            settings.settings.collect { weekStartsMonday = it.weekStartsMonday }
        }
    }

    fun logic(): HabitLogic = HabitLogic(if (weekStartsMonday) DayOfWeek.MONDAY else DayOfWeek.SUNDAY)

    suspend fun logicFromSettings(): HabitLogic {
        val s = settings.settings.first()
        return HabitLogic(if (s.weekStartsMonday) DayOfWeek.MONDAY else DayOfWeek.SUNDAY)
    }

    /** Switches the launcher icon by toggling activity-aliases. */
    fun applyAppIcon(icon: AppIcon) {
        val pm = context.packageManager
        val pkg = context.packageName
        val target = ComponentName(pkg, "$pkg.${icon.alias}")
        // Enable the target first so a launcher entry always exists, then disable the other aliases.
        // MainActivity itself stays enabled so notifications and widgets can always open it.
        pm.setComponentEnabledSetting(target, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        AppIcon.entries.filter { it != icon }.forEach {
            pm.setComponentEnabledSetting(ComponentName(pkg, "$pkg.${it.alias}"), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
    }
}

val Context.dayriseApp: DayriseApp get() = applicationContext as DayriseApp
val Context.container: AppContainer get() = dayriseApp.container
