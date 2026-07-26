package com.nekonf.nekostatus

import android.app.Application
import com.nekonf.nekostatus.core.data.SettingsRepository
import com.nekonf.nekostatus.core.data.WidgetSettingsRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class NekoApplication : Application() {
    @Inject lateinit var widgetSettingsRepository: WidgetSettingsRepository

    @Inject lateinit var settingsRepository: SettingsRepository

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val settings = widgetSettingsRepository.settings.value
        WidgetRefreshScheduler.sync(this, settings.enabled, settings.refreshIntervalMinutes)
        UpdateManager.restoreState(this)
        applicationScope.launch {
            settingsRepository.updateSettings.distinctUntilChanged().collect { updateSettings ->
                UpdateManager.onSettingsChanged(this@NekoApplication, updateSettings)
                UpdateManager.syncSchedule(this@NekoApplication, updateSettings)
                if (updateSettings.automaticChecks) UpdateManager.checkOnLaunch(this@NekoApplication)
            }
        }
    }
}
