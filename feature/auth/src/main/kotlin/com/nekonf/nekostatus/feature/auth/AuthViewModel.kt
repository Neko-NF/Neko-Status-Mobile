package com.nekonf.nekostatus.feature.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nekonf.nekostatus.core.data.AuthRepository
import com.nekonf.nekostatus.core.data.SettingsRepository
import com.nekonf.nekostatus.core.model.OperationResult
import com.nekonf.nekostatus.core.model.ServerConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AuthMode { LOGIN, REGISTER, DEVICE_KEY }

data class AuthUiState(
    val mode: AuthMode = AuthMode.LOGIN,
    val username: String = "",
    val password: String = "",
    val deviceKey: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

@HiltViewModel
class AuthViewModel
    @Inject
    constructor(
        private val authRepository: AuthRepository,
        private val settingsRepository: SettingsRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(AuthUiState())
        val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()
        val serverConfig: StateFlow<ServerConfig> =
            settingsRepository.serverConfig
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ServerConfig())

        fun setMode(mode: AuthMode) = _uiState.update { it.copy(mode = mode, errorMessage = null) }

        fun setUsername(value: String) = _uiState.update { it.copy(username = value, errorMessage = null) }

        fun setPassword(value: String) = _uiState.update { it.copy(password = value, errorMessage = null) }

        fun setDeviceKey(value: String) = _uiState.update { it.copy(deviceKey = value, errorMessage = null) }

        fun submit() {
            if (_uiState.value.isLoading) return
            viewModelScope.launch {
                val state = _uiState.value
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                val authentication =
                    when (state.mode) {
                        AuthMode.LOGIN -> authRepository.login(state.username, state.password)
                        AuthMode.REGISTER -> authRepository.register(state.username, state.password)
                        AuthMode.DEVICE_KEY -> authRepository.validateManualDeviceKey(state.deviceKey)
                    }
                val error =
                    authentication.failureMessageOrNull()
                        ?: if (state.mode != AuthMode.DEVICE_KEY && authentication is OperationResult.Success) {
                            authRepository.ensureDeviceCredential().failureMessageOrNull()
                        } else {
                            null
                        }
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = error)
                }
            }
        }

        fun pair(token: String) {
            if (_uiState.value.isLoading) return
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }
                when (val result = authRepository.pair(token)) {
                    is OperationResult.Success -> _uiState.update { it.copy(isLoading = false) }
                    is OperationResult.Failure ->
                        _uiState.update {
                            it.copy(isLoading = false, errorMessage = result.message)
                        }
                }
            }
        }

        fun saveServer(config: ServerConfig) {
            viewModelScope.launch { settingsRepository.updateServer(config) }
        }

        private fun OperationResult<*>.failureMessageOrNull(): String? = (this as? OperationResult.Failure)?.message
    }
