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
import com.nekonf.nekostatus.core.model.ProfileUpdate
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
import kotlinx.coroutines.flow.MutableStateFlow
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
    val profileSaving: Boolean = false,
    val profileSaved: Boolean = false,
    val profileError: String? = null,
    val passwordSaving: Boolean = false,
    val passwordSaved: Boolean = false,
    val passwordError: String? = null,
)

@HiltViewModel
@Suppress("LongParameterList", "TooManyFunctions")
class SettingsViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        private val authRepository: AuthRepository,
        private val sessionRepository: SessionRepository,
        private val diagnosticsRepository: DiagnosticsRepository,
        private val widgetSettingsRepository: WidgetSettingsRepository,
        private val widgetRepository: WidgetRepository,
        private val reportingStateStore: ReportingStateStore,
    ) : ViewModel() {
        private val profileState = MutableStateFlow(ProfileOperationState())
        private val passwordState = MutableStateFlow(ProfileOperationState())

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
            }.combine(profileState) { state, profile ->
                state.copy(
                    profileSaving = profile.saving,
                    profileSaved = profile.saved,
                    profileError = profile.error,
                )
            }.combine(passwordState) { state, password ->
                state.copy(
                    passwordSaving = password.saving,
                    passwordSaved = password.saved,
                    passwordError = password.error,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SettingsUiState())

        val logs: StateFlow<List<DiagnosticLogEntity>> =
            diagnosticsRepository.logs
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

        init {
            viewModelScope.launch {
                when (val result = authRepository.restoreProfile()) {
                    is OperationResult.Success -> Unit
                    is OperationResult.Failure ->
                        if (result.code !in setOf("NO_SESSION", "NETWORK_ERROR")) {
                            profileState.value = ProfileOperationState(error = result.message)
                        }
                }
            }
        }

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
                    widgetSettingsRepository.resetAccountScope()
                    widgetRepository.clear()
                    reportingStateStore.reset()
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
                when (val result = widgetRepository.refresh(settings)) {
                    is OperationResult.Success -> {
                        val credential = sessionRepository.widgetCredential.value
                        val accessNormalized =
                            if (credential?.userType == "admin") {
                                settings
                            } else {
                                settings.copy(
                                    displayMode = com.nekonf.nekostatus.core.model.WidgetDisplayMode.SINGLE,
                                    targetUserId = credential?.userId,
                                )
                            }
                        val selectedUser =
                            result.value.users.firstOrNull { it.userId == accessNormalized.targetUserId }
                                ?: result.value.users.firstOrNull()
                        val validSelectedIds =
                            accessNormalized.selectedDeviceIds.filter { selectedId ->
                                selectedUser?.devices?.any { it.deviceId == selectedId } == true
                            }
                        val normalized =
                            accessNormalized.copy(
                                targetUserId =
                                    accessNormalized.targetUserId
                                        ?: selectedUser?.userId,
                                selectedDeviceIds =
                                    validSelectedIds.ifEmpty {
                                        selectedUser?.devices.orEmpty().take(2).map { it.deviceId }
                                    },
                                targetDeviceId =
                                    selectedUser?.devices?.firstOrNull {
                                        it.deviceId == accessNormalized.targetDeviceId
                                    }?.deviceId
                                        ?: selectedUser?.devices?.firstOrNull {
                                            !it.screenshotThumbnailUrl.isNullOrBlank() ||
                                                !it.screenshotUrl.isNullOrBlank()
                                        }?.deviceId
                                        ?: selectedUser?.devices?.firstOrNull()?.deviceId,
                            )
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

        fun saveProfile(
            username: String,
            email: String,
            avatar: String?,
        ) {
            profileState.value = ProfileOperationState(saving = true)
            viewModelScope.launch {
                when (
                    val result =
                        authRepository.updateProfile(
                            ProfileUpdate(
                                username = username,
                                email = email,
                                avatar = avatar,
                            ),
                        )
                ) {
                    is OperationResult.Success -> {
                        profileState.value = ProfileOperationState(saved = true)
                        if (widgetSettingsRepository.settings.value.enabled) {
                            widgetRepository.refresh(widgetSettingsRepository.settings.value)
                        }
                    }
                    is OperationResult.Failure -> profileState.value = ProfileOperationState(error = result.message)
                }
            }
        }

        fun changePassword(
            currentPassword: String,
            newPassword: String,
        ) {
            passwordState.value = ProfileOperationState(saving = true)
            viewModelScope.launch {
                when (
                    val result =
                        authRepository.updateProfile(
                            ProfileUpdate(
                                currentPassword = currentPassword,
                                newPassword = newPassword,
                            ),
                        )
                ) {
                    is OperationResult.Success -> passwordState.value = ProfileOperationState(saved = true)
                    is OperationResult.Failure -> passwordState.value = ProfileOperationState(error = result.message)
                }
            }
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

        fun logout(onComplete: () -> Unit = {}) {
            viewModelScope.launch {
                settingsRepository.updateReporting(uiState.value.reportingSettings.copy(enabled = false))
                widgetSettingsRepository.resetAccountScope()
                widgetRepository.clear()
                reportingStateStore.reset()
                authRepository.logout()
                onComplete()
            }
        }

        fun clearDiagnostics() {
            viewModelScope.launch { diagnosticsRepository.clear() }
        }
    }

private data class ProfileOperationState(
    val saving: Boolean = false,
    val saved: Boolean = false,
    val error: String? = null,
)
