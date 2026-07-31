package com.nekonf.nekostatus

import android.app.Application
import com.nekonf.nekostatus.core.data.ReportingStateStore
import com.nekonf.nekostatus.core.data.SessionRepository
import com.nekonf.nekostatus.core.data.SettingsRepository
import com.nekonf.nekostatus.core.data.WidgetRepository
import com.nekonf.nekostatus.core.data.WidgetSettingsRepository
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class NekoApplication : Application() {
    @Inject lateinit var widgetSettingsRepository: WidgetSettingsRepository

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var sessionRepository: SessionRepository

    @Inject lateinit var widgetRepository: WidgetRepository

    @Inject lateinit var reportingStateStore: ReportingStateStore

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
        applicationScope.launch {
            settingsRepository.reportingSettings.distinctUntilChanged().collect { reportingSettings ->
                KeepAliveReminderScheduler.sync(this@NekoApplication, reportingSettings)
            }
        }
        applicationScope.launch {
            if (sessionRepository.deviceCredential.value == null) cleanupAccountState()
            sessionRepository.accountBoundaryEvents.collect { cleanupAccountState() }
        }
    }

    private suspend fun cleanupAccountState() {
        ReportingService.stopImmediately(this)
        val reportingSettings = settingsRepository.reportingSettings.first()
        if (reportingSettings.enabled) {
            settingsRepository.updateReporting(reportingSettings.copy(enabled = false))
        }
        widgetSettingsRepository.resetAccountScope()
        widgetRepository.clear()
        reportingStateStore.reset()
        WidgetRefreshScheduler.cancelAll(this)
        WidgetImageCache.clear(this)
        NekoWidgetRenderer.clearAllPages(this)
        KeepAliveReminderScheduler.sync(this, reportingSettings.copy(enabled = false))
        NekoWidgetRenderer.updateAll(this)
        NekoSnapshotWidgetRenderer.updateAll(this)
    }
}
