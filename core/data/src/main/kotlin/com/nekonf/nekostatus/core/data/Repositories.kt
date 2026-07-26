package com.nekonf.nekostatus.core.data

import android.os.Build
import com.nekonf.nekostatus.core.database.DiagnosticLogDao
import com.nekonf.nekostatus.core.database.DiagnosticLogEntity
import com.nekonf.nekostatus.core.model.AuthSession
import com.nekonf.nekostatus.core.model.DeviceCredential
import com.nekonf.nekostatus.core.model.DeviceSnapshot
import com.nekonf.nekostatus.core.model.OperationResult
import com.nekonf.nekostatus.core.model.ReportOutcome
import com.nekonf.nekostatus.core.model.ReportingHealth
import com.nekonf.nekostatus.core.model.ServerCapabilities
import com.nekonf.nekostatus.core.model.UserProfile
import com.nekonf.nekostatus.core.network.NekoApiFactory
import com.nekonf.nekostatus.core.network.dto.ApiErrorResponse
import com.nekonf.nekostatus.core.network.dto.AuthRequest
import com.nekonf.nekostatus.core.network.dto.AuthResponse
import com.nekonf.nekostatus.core.network.dto.DeviceKeyRequest
import com.nekonf.nekostatus.core.network.dto.HandshakeRequest
import com.nekonf.nekostatus.core.network.dto.StatusPayload
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private const val PAIRING_MAX_ATTEMPTS = 30
private const val PAIRING_POLL_INTERVAL_MS = 2_000L
private const val BATTERY_MIN_PERCENT = 0
private const val BATTERY_MAX_PERCENT = 100
private const val HTTP_UNAUTHORIZED = 401
private const val HTTP_FORBIDDEN = 403
private const val HTTP_NOT_FOUND = 404
private const val HTTP_GONE = 410
private const val CAPABILITY_NOT_IMPLEMENTED = 501
private const val DIAGNOSTIC_RETENTION_DAYS = 7L
private const val HOURS_PER_DAY = 24L
private const val MINUTES_PER_HOUR = 60L
private const val SECONDS_PER_MINUTE = 60L
private const val MILLIS_PER_SECOND = 1_000L
private val TERMINAL_REPORT_CODES = setOf(HTTP_UNAUTHORIZED, HTTP_FORBIDDEN, HTTP_NOT_FOUND)

@Singleton
class AuthRepository
    @Inject
    constructor(
        private val apiFactory: NekoApiFactory,
        private val settingsRepository: SettingsRepository,
        private val sessionRepository: SessionRepository,
        private val installationRepository: InstallationRepository,
        private val json: Json,
    ) {
        suspend fun login(
            username: String,
            password: String,
        ): OperationResult<AuthSession> = authenticate { it.login(AuthRequest(username.trim(), password)) }

        suspend fun register(
            username: String,
            password: String,
        ): OperationResult<AuthSession> = authenticate { it.register(AuthRequest(username.trim(), password)) }

        suspend fun restoreProfile(): OperationResult<AuthSession> {
            val current =
                sessionRepository.session.value
                    ?: return OperationResult.Failure("NO_SESSION", "尚未登录", terminal = true)
            val api = apiFactory.create(settingsRepository.serverConfig.first().activeUrl)
            return request {
                val response = api.me("Bearer ${current.token}")
                response.toOperation { body ->
                    val user = body.user?.toProfile() ?: current.user
                    AuthSession(current.token, user).also(sessionRepository::saveSession)
                }
            }
        }

        suspend fun ensureDeviceCredential(): OperationResult<DeviceCredential> {
            sessionRepository.deviceCredential.value?.let { return OperationResult.Success(it) }
            val session =
                sessionRepository.session.value
                    ?: return OperationResult.Failure("NO_SESSION", "请先登录", terminal = true)
            val api = apiFactory.create(settingsRepository.serverConfig.first().activeUrl)
            return request {
                val response =
                    api.generateDeviceKey(
                        "Bearer ${session.token}",
                        DeviceKeyRequest(
                            deviceName = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                            deviceFingerprint = installationRepository.installationId,
                        ),
                    )
                response.toOperation { body ->
                    val key = body.deviceKey ?: body.key ?: error("服务端未返回设备密钥")
                    DeviceCredential(key, body.deviceId, "${Build.MANUFACTURER} ${Build.MODEL}").also(
                        sessionRepository::saveDeviceCredential,
                    )
                }
            }
        }

        suspend fun pair(token: String): OperationResult<DeviceCredential> {
            val api = apiFactory.create(settingsRepository.serverConfig.first().activeUrl)
            val model = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            return request {
                val initial = api.handshake(HandshakeRequest(token.trim(), model))
                if (!initial.isSuccessful) return@request initial.toFailure(json)
                initial.body()?.toCredential(model)?.let {
                    sessionRepository.saveDeviceCredential(it)
                    return@request OperationResult.Success(it)
                }
                repeat(PAIRING_MAX_ATTEMPTS) {
                    delay(PAIRING_POLL_INTERVAL_MS)
                    val poll = api.handshakeResult(token.trim())
                    if (poll.isSuccessful) {
                        poll.body()?.toCredential(model)?.let {
                            sessionRepository.saveDeviceCredential(it)
                            return@request OperationResult.Success(it)
                        }
                    } else if (poll.code() == HTTP_NOT_FOUND || poll.code() == HTTP_GONE) {
                        return@request poll.toFailure(json)
                    }
                }
                OperationResult.Failure("PAIRING_TIMEOUT", "配对等待超时")
            }
        }

        suspend fun validateManualDeviceKey(key: String): OperationResult<DeviceCredential> {
            val api = apiFactory.create(settingsRepository.serverConfig.first().activeUrl)
            return request {
                val response = api.validateDevice("Bearer ${key.trim()}", installationRepository.installationId)
                response.toOperation { body ->
                    if (!body.valid) error(body.message ?: "设备密钥无效")
                    DeviceCredential(key.trim(), body.deviceId, body.deviceName).also(sessionRepository::saveDeviceCredential)
                }
            }
        }

        fun logout() = sessionRepository.clearAll()

        private suspend fun authenticate(
            call: suspend (com.nekonf.nekostatus.core.network.NekoApi) -> Response<AuthResponse>,
        ): OperationResult<AuthSession> {
            val api = apiFactory.create(settingsRepository.serverConfig.first().activeUrl)
            return request {
                call(api).toOperation { body ->
                    val token = body.token ?: error("服务端未返回登录令牌")
                    val user = body.user?.toProfile() ?: error("服务端未返回用户资料")
                    AuthSession(token, user).also(sessionRepository::saveSession)
                }
            }
        }

        private suspend fun <T> request(block: suspend () -> OperationResult<T>): OperationResult<T> =
            try {
                block()
            } catch (error: IOException) {
                OperationResult.Failure("NETWORK_ERROR", error.message ?: "网络不可用")
            } catch (error: Exception) {
                OperationResult.Failure("CLIENT_ERROR", error.message ?: "客户端处理失败")
            }

        private fun com.nekonf.nekostatus.core.network.dto.UserDto.toProfile() =
            UserProfile(
                id = id,
                username = username,
                email = email,
                avatarUrl = avatarUrl ?: avatar,
            )

        private fun com.nekonf.nekostatus.core.network.dto.HandshakeResponse.toCredential(model: String): DeviceCredential? {
            val resolvedKey = deviceKey ?: key ?: return null
            return DeviceCredential(resolvedKey, deviceId, model)
        }
    }

@Singleton
class StatusRepository
    @Inject
    constructor(
        private val apiFactory: NekoApiFactory,
        private val settingsRepository: SettingsRepository,
        private val sessionRepository: SessionRepository,
        private val json: Json,
    ) {
        suspend fun report(
            snapshot: DeviceSnapshot,
            clientVersion: String,
            iconPng: ByteArray? = null,
        ): ReportOutcome {
            val credential =
                sessionRepository.deviceCredential.value
                    ?: return ReportOutcome.Terminal("MISSING_DEVICE_KEY", "设备尚未绑定")
            val api = apiFactory.create(settingsRepository.serverConfig.first().activeUrl)
            val payload =
                StatusPayload(
                    deviceKey = credential.deviceKey,
                    deviceFingerprint = snapshot.installationId,
                    clientVersion = clientVersion,
                    appVersion = clientVersion,
                    appName = snapshot.appName,
                    packageName = snapshot.packageName,
                    status = snapshot.presenceState.name.lowercase(),
                    screenStatus = snapshot.screenState.name.lowercase(),
                    batteryLevel = snapshot.batteryLevel.coerceIn(BATTERY_MIN_PERCENT, BATTERY_MAX_PERCENT),
                    isCharging = snapshot.isCharging,
                    music = snapshot.media,
                )
            val data = json.encodeToString(payload).toRequestBody("application/json".toMediaType())
            val icon =
                iconPng?.let {
                    MultipartBody.Part.createFormData("file", "icon.png", it.toRequestBody("image/png".toMediaType()))
                }
            return try {
                val response = api.reportStatus("Bearer ${credential.deviceKey}", data, icon)
                val error = response.errorBodyParsed(json)
                val outcome =
                    when {
                        response.isSuccessful -> ReportOutcome.Success(response.body()?.timestamp)
                        else ->
                            ReportResponsePolicy.failure(
                                httpCode = response.code(),
                                serverCode = response.errorCode(error),
                                serverMessage = error?.message ?: error?.error,
                                retryAfterHeader = response.headers()["Retry-After"],
                            )
                    }
                if (response.code() in TERMINAL_REPORT_CODES) sessionRepository.clearAccountAccess()
                outcome
            } catch (error: IOException) {
                ReportOutcome.Retryable("NETWORK_ERROR", error.message ?: "网络不可用")
            }
        }
    }

@Singleton
class CapabilityRepository
    @Inject
    constructor(
        private val apiFactory: NekoApiFactory,
        private val settingsRepository: SettingsRepository,
        private val sessionRepository: SessionRepository,
    ) {
        val capabilities: Flow<ServerCapabilities> = settingsRepository.serverCapabilities

        suspend fun refresh(): ServerCapabilities {
            val server = settingsRepository.serverConfig.first().activeUrl
            val token = sessionRepository.session.value?.token

            fun supported(code: Int) = code != HTTP_NOT_FOUND && code != CAPABILITY_NOT_IMPLEMENTED
            val result =
                ServerCapabilities(
                    history = supported(apiFactory.probe(server, "/api/v1/status/history?limit=1", token)),
                    announcements = supported(apiFactory.probe(server, "/api/announcements?limit=1", token)),
                    activity = supported(apiFactory.probe(server, "/api/activity/agent/bootstrap", token)),
                    multiDeviceWidget = supported(apiFactory.probe(server, "/api/v2/widget/status", null)),
                    checkedAtEpochMs = System.currentTimeMillis(),
                )
            settingsRepository.updateServerCapabilities(result)
            return result
        }
    }

@Singleton
class ReportingStateStore
    @Inject
    constructor() {
        private val _health = MutableStateFlow(ReportingHealth())
        val health: StateFlow<ReportingHealth> = _health.asStateFlow()

        fun update(transform: (ReportingHealth) -> ReportingHealth) {
            _health.value = transform(_health.value)
        }
    }

@Singleton
class DiagnosticsRepository
    @Inject
    constructor(
        private val dao: DiagnosticLogDao,
    ) {
        val logs: Flow<List<DiagnosticLogEntity>> = dao.observeLatest()

        suspend fun log(
            level: String,
            category: String,
            message: String,
        ) {
            dao.insert(
                DiagnosticLogEntity(
                    timestampEpochMs = System.currentTimeMillis(),
                    level = level,
                    category = category,
                    message = redactDiagnosticMessage(message),
                ),
            )
            dao.deleteOlderThan(
                System.currentTimeMillis() -
                    DIAGNOSTIC_RETENTION_DAYS * HOURS_PER_DAY * MINUTES_PER_HOUR * SECONDS_PER_MINUTE * MILLIS_PER_SECOND,
            )
        }

        suspend fun clear() = dao.clear()
    }

private fun <T, R> Response<T>.toOperation(transform: (T) -> R): OperationResult<R> {
    if (!isSuccessful) return toFailure(Json { ignoreUnknownKeys = true })
    val body = body() ?: return OperationResult.Failure("EMPTY_RESPONSE", "服务端响应为空")
    return try {
        OperationResult.Success(transform(body))
    } catch (error: Exception) {
        OperationResult.Failure("INVALID_RESPONSE", error.message ?: "服务端响应无效")
    }
}

private fun Response<*>.toFailure(json: Json): OperationResult.Failure {
    val error = errorBodyParsed(json)
    return OperationResult.Failure(
        code = errorCode(error),
        message = errorMessage(error, "请求失败 HTTP ${code()}"),
        retryAfterSeconds = headers()["Retry-After"]?.toLongOrNull(),
        terminal = code() in setOf(HTTP_UNAUTHORIZED, HTTP_FORBIDDEN, HTTP_NOT_FOUND, HTTP_GONE),
    )
}

private fun Response<*>.errorBodyParsed(json: Json): ApiErrorResponse? =
    runCatching {
        errorBody()?.string()?.let { json.decodeFromString<ApiErrorResponse>(it) }
    }.getOrNull()

private fun Response<*>.errorCode(parsed: ApiErrorResponse?): String {
    return parsed?.code ?: parsed?.errorCode ?: "HTTP_${code()}"
}

private fun Response<*>.errorMessage(
    parsed: ApiErrorResponse?,
    fallback: String,
): String {
    return parsed?.message ?: parsed?.error ?: fallback
}
