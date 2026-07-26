package com.nekonf.nekostatus.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nekonf.nekostatus.core.data.AuthRepository
import com.nekonf.nekostatus.core.data.DiagnosticsRepository
import com.nekonf.nekostatus.core.data.ReportingStateStore
import com.nekonf.nekostatus.core.data.SessionRepository
import com.nekonf.nekostatus.core.data.SettingsRepository
import com.nekonf.nekostatus.core.data.WidgetRepository
import com.nekonf.nekostatus.core.data.WidgetSettingsRepository
import com.nekonf.nekostatus.core.database.DiagnosticLogEntity
import com.nekonf.nekostatus.core.model.OperationResult
import com.nekonf.nekostatus.core.model.ReportingHealth
import com.nekonf.nekostatus.core.model.ReportingSettings
import com.nekonf.nekostatus.core.model.ServerConfig
import com.nekonf.nekostatus.core.model.UpdateSettings
import com.nekonf.nekostatus.core.model.UpdateSource
import com.nekonf.nekostatus.core.model.UserProfile
import com.nekonf.nekostatus.core.model.WidgetFeedState
import com.nekonf.nekostatus.core.model.WidgetSettings
import com.nekonf.nekostatus.core.model.normalizeGitHubRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val themeMode: String = "system",
    val dynamicColor: Boolean = false,
    val serverConfig: ServerConfig = ServerConfig(),
    val user: UserProfile? = null,
    val widgetSettings: WidgetSettings = WidgetSettings(),
    val reportingHealth: ReportingHealth = ReportingHealth(),
    val reportingSettings: ReportingSettings = ReportingSettings(),
    val updateSettings: UpdateSettings = UpdateSettings(),
    val widgetFeedState: WidgetFeedState = WidgetFeedState(),
    val widgetUsername: String? = null,
    val widgetUserType: String? = null,
)

@HiltViewModel
@Suppress("LongParameterList")
class SettingsViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val authRepository: AuthRepository,
        private val sessionRepository: SessionRepository,
        private val diagnosticsRepository: DiagnosticsRepository,
        private val widgetSettingsRepository: WidgetSettingsRepository,
        private val widgetRepository: WidgetRepository,
        reportingStateStore: ReportingStateStore,
    ) : ViewModel() {
        val uiState: StateFlow<SettingsUiState> =
            combine(
                settingsRepository.themeMode,
                settingsRepository.dynamicColor,
                settingsRepository.serverConfig,
                sessionRepository.session,
                widgetSettingsRepository.settings,
            ) { theme, dynamic, server, session, widgetSettings ->
                SettingsUiState(theme, dynamic, server, session?.user, widgetSettings)
            }.combine(reportingStateStore.health) { state, health ->
                state.copy(reportingHealth = health)
            }.combine(settingsRepository.reportingSettings) { state, settings ->
                state.copy(reportingSettings = settings)
            }.combine(settingsRepository.updateSettings) { state, settings ->
                state.copy(updateSettings = settings)
            }.combine(widgetRepository.state) { state, widgetFeedState ->
                state.copy(widgetFeedState = widgetFeedState)
            }.combine(sessionRepository.widgetCredential) { state, credential ->
                state.copy(widgetUsername = credential?.username, widgetUserType = credential?.userType)
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

        val logs: StateFlow<List<DiagnosticLogEntity>> =
            diagnosticsRepository.logs
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        fun setTheme(
            mode: String,
            dynamicColor: Boolean,
        ) {
            viewModelScope.launch { settingsRepository.setTheme(mode, dynamicColor) }
        }

        fun saveServer(config: ServerConfig) {
            viewModelScope.launch {
                val serverChanged = config.activeUrl != uiState.value.serverConfig.activeUrl
                if (serverChanged) {
                    settingsRepository.updateReporting(uiState.value.reportingSettings.copy(enabled = false))
                    widgetRepository.clear()
                    sessionRepository.clearAccountAccess()
                }
                settingsRepository.updateServer(config)
            }
        }

        fun setWidgetSettings(settings: WidgetSettings) {
            widgetSettingsRepository.update(settings)
        }

        fun setWidgetEnabled(
            enabled: Boolean,
            onChanged: (Boolean) -> Unit,
        ) {
            if (!enabled) {
                val settings = widgetSettingsRepository.settings.value.copy(enabled = false)
                widgetSettingsRepository.update(settings)
                widgetRepository.disable()
                onChanged(false)
                return
            }
            viewModelScope.launch {
                val settings = widgetSettingsRepository.settings.value.copy(enabled = true)
                when (widgetRepository.refresh(settings)) {
                    is OperationResult.Success -> {
                        val credential = sessionRepository.widgetCredential.value
                        val normalized =
                            if (credential?.userType == "admin") {
                                settings
                            } else {
                                settings.copy(
                                    displayMode = com.nekonf.nekostatus.core.model.WidgetDisplayMode.SINGLE,
                                    targetUserId = credential?.userId,
                                )
                            }
                        widgetSettingsRepository.update(normalized)
                        onChanged(true)
                    }
                    is OperationResult.Failure -> onChanged(false)
                }
            }
        }

        fun refreshWidget(onFinished: (Boolean) -> Unit = {}) {
            viewModelScope.launch {
                val result = widgetRepository.refresh(widgetSettingsRepository.settings.value)
                onFinished(result is OperationResult.Success)
            }
        }

        fun updateReportingSettings(settings: ReportingSettings) {
            viewModelScope.launch { settingsRepository.updateReporting(settings) }
        }

        fun setAutomaticUpdates(
            automaticChecks: Boolean? = null,
            automaticDownload: Boolean? = null,
        ) {
            viewModelScope.launch {
                automaticChecks?.let { settingsRepository.setAutomaticUpdateChecks(it) }
                automaticDownload?.let { settingsRepository.setAutomaticUpdateDownload(it) }
            }
        }

        fun setUpdateRepository(
            source: UpdateSource,
            repository: String = uiState.value.updateSettings.customRepository,
            onSaved: ((Boolean) -> Unit)? = null,
        ) {
            val normalized = normalizeGitHubRepository(repository)
            if (source == UpdateSource.CUSTOM && normalized == null) {
                onSaved?.invoke(false)
                return
            }
            viewModelScope.launch {
                val result =
                    runCatching {
                        settingsRepository.setUpdateRepository(source, normalized.orEmpty())
                    }
                onSaved?.invoke(result.isSuccess)
            }
        }

        fun logout() {
            viewModelScope.launch {
                settingsRepository.updateReporting(uiState.value.reportingSettings.copy(enabled = false))
                widgetRepository.clear()
                authRepository.logout()
            }
        }

        fun clearDiagnostics() {
            viewModelScope.launch { diagnosticsRepository.clear() }
        }
    }
