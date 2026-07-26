package com.nekonf.nekostatus

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nekonf.nekostatus.core.data.CapabilityRepository
import com.nekonf.nekostatus.core.data.SessionRepository
import com.nekonf.nekostatus.core.data.SettingsRepository
import com.nekonf.nekostatus.core.model.AuthSession
import com.nekonf.nekostatus.core.model.DeviceCredential
import com.nekonf.nekostatus.core.model.ServerCapabilities
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AppUiState(
    val session: AuthSession? = null,
    val deviceCredential: DeviceCredential? = null,
    val onboardingComplete: Boolean = false,
    val themeMode: String = "system",
    val dynamicColor: Boolean = false,
    val capabilities: ServerCapabilities = ServerCapabilities(),
) {
    val canReport: Boolean get() = deviceCredential != null
}

@HiltViewModel
class AppViewModel
    @Inject
    constructor(
        private val settingsRepository: SettingsRepository,
        sessionRepository: SessionRepository,
        capabilityRepository: CapabilityRepository,
    ) : ViewModel() {
        val uiState: StateFlow<AppUiState> =
            combine(
                sessionRepository.session,
                sessionRepository.deviceCredential,
                settingsRepository.onboardingComplete,
                settingsRepository.themeMode,
                settingsRepository.dynamicColor,
                capabilityRepository.capabilities,
            ) { values ->
                @Suppress("UNCHECKED_CAST")
                AppUiState(
                    session = values[0] as AuthSession?,
                    deviceCredential = values[1] as DeviceCredential?,
                    onboardingComplete = values[2] as Boolean,
                    themeMode = values[3] as String,
                    dynamicColor = values[4] as Boolean,
                    capabilities = values[5] as ServerCapabilities,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppUiState())

        init {
            viewModelScope.launch {
                sessionRepository.deviceCredential.filterNotNull().collect {
                    runCatching { capabilityRepository.refresh() }
                }
            }
        }

        fun completeOnboarding() {
            viewModelScope.launch { settingsRepository.completeOnboarding() }
        }
    }
