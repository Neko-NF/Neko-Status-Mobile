package com.nekonf.nekostatus.core.data

import android.content.Context
import com.nekonf.nekostatus.core.model.OperationResult
import com.nekonf.nekostatus.core.model.WidgetAvailability
import com.nekonf.nekostatus.core.model.WidgetCredential
import com.nekonf.nekostatus.core.model.WidgetDeviceStatus
import com.nekonf.nekostatus.core.model.WidgetDisplayMode
import com.nekonf.nekostatus.core.model.WidgetFeed
import com.nekonf.nekostatus.core.model.WidgetFeedState
import com.nekonf.nekostatus.core.model.WidgetSettings
import com.nekonf.nekostatus.core.model.WidgetUserStatus
import com.nekonf.nekostatus.core.network.NekoApiFactory
import com.nekonf.nekostatus.core.network.dto.ApiErrorResponse
import com.nekonf.nekostatus.core.network.dto.WidgetDeviceDto
import com.nekonf.nekostatus.core.network.dto.WidgetStatusResponse
import com.nekonf.nekostatus.core.network.dto.WidgetTokenRequest
import com.nekonf.nekostatus.core.network.dto.WidgetUserDto
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import retrofit2.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

data class WidgetCacheSnapshot(
    val feed: WidgetFeed?,
    val lastAttemptEpochMs: Long?,
    val errorCode: String?,
)

object WidgetFeedStore {
    private const val PREFERENCES = "neko-widget-feed"
    private const val FEED = "feed"
    private const val LAST_ATTEMPT = "last_attempt"
    private const val ERROR_CODE = "error_code"
    private val json = Json { ignoreUnknownKeys = true }

    fun read(context: Context): WidgetCacheSnapshot {
        val values = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val feed =
            values.getString(FEED, null)?.let {
                runCatching { json.decodeFromString<WidgetFeed>(it) }.getOrNull()
            }
        return WidgetCacheSnapshot(
            feed = feed,
            lastAttemptEpochMs = values.getLong(LAST_ATTEMPT, -1L).takeIf { it >= 0L },
            errorCode = values.getString(ERROR_CODE, null),
        )
    }

    fun writeSuccess(
        context: Context,
        feed: WidgetFeed,
    ) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(FEED, json.encodeToString(feed))
            .putLong(LAST_ATTEMPT, System.currentTimeMillis())
            .remove(ERROR_CODE)
            .apply()
    }

    fun writeFailure(
        context: Context,
        code: String,
    ) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putLong(LAST_ATTEMPT, System.currentTimeMillis())
            .putString(ERROR_CODE, code)
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().apply()
    }
}

@Singleton
class WidgetRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val apiFactory: NekoApiFactory,
        private val settingsRepository: SettingsRepository,
        private val sessionRepository: SessionRepository,
        private val json: Json,
    ) {
        private val cached = WidgetFeedStore.read(context)
        private val _state =
            MutableStateFlow(
                WidgetFeedState(
                    availability = if (cached.feed == null) WidgetAvailability.DISABLED else WidgetAvailability.READY,
                    feed = cached.feed,
                    errorCode = cached.errorCode,
                ),
            )
        val state: StateFlow<WidgetFeedState> = _state.asStateFlow()

        suspend fun ensureCredential(): OperationResult<WidgetCredential> {
            sessionRepository.widgetCredential.value?.let { return OperationResult.Success(it) }
            val device =
                sessionRepository.deviceCredential.value
                    ?: return OperationResult.Failure("MISSING_DEVICE_KEY", "请先绑定设备", terminal = true)
            val api = apiFactory.createWidget(settingsRepository.serverConfig.first().activeUrl)
            return try {
                val response =
                    api.generateWidgetToken(
                        authorization = "Bearer ${device.deviceKey}",
                        request = WidgetTokenRequest(device.deviceKey),
                    )
                if (response.isSuccessful) {
                    val body = response.body()
                    val token = body?.widgetToken
                    if (body?.success == true && !token.isNullOrBlank()) {
                        OperationResult.Success(
                            WidgetCredential(
                                token = token,
                                userId = body.userId.stringValue(),
                                username = body.username,
                                userType = body.userType,
                                expiresAt = body.expiresAt,
                            ).also(sessionRepository::saveWidgetCredential),
                        )
                    } else {
                        OperationResult.Failure(
                            body?.code ?: body?.error ?: "INVALID_WIDGET_TOKEN_RESPONSE",
                            body?.message ?: "服务端未返回小组件凭据",
                        )
                    }
                } else {
                    val failure = response.toWidgetFailure(json)
                    if (response.code() == 401 || response.code() == 403) sessionRepository.clearAccountAccess()
                    failure
                }
            } catch (error: IOException) {
                OperationResult.Failure("NETWORK_ERROR", error.message ?: "网络不可用")
            }
        }

        suspend fun refresh(settings: WidgetSettings): OperationResult<WidgetFeed> {
            _state.value = _state.value.copy(availability = WidgetAvailability.LOADING, errorCode = null, errorMessage = null)
            return refreshInternal(settings, allowCredentialRenewal = true)
        }

        fun disable() {
            _state.value = WidgetFeedState(WidgetAvailability.DISABLED, feed = _state.value.feed)
        }

        fun clear() {
            sessionRepository.clearWidgetCredential()
            WidgetFeedStore.clear(context)
            _state.value = WidgetFeedState()
        }

        private suspend fun refreshInternal(
            settings: WidgetSettings,
            allowCredentialRenewal: Boolean,
        ): OperationResult<WidgetFeed> {
            val credentialResult = ensureCredential()
            if (credentialResult is OperationResult.Failure) return fail(credentialResult)
            val credential = (credentialResult as OperationResult.Success).value
            val api = apiFactory.createWidget(settingsRepository.serverConfig.first().activeUrl)
            return try {
                val response =
                    api.widgetStatus(
                        authorization = "Bearer ${credential.token}",
                        userId = settings.targetUserId.takeIf { settings.displayMode == WidgetDisplayMode.SINGLE },
                        limit = if (settings.displayMode == WidgetDisplayMode.ALL) 4 else null,
                    )
                handleStatusResponse(response, settings, allowCredentialRenewal)
            } catch (error: IOException) {
                fail(OperationResult.Failure("NETWORK_ERROR", error.message ?: "网络不可用"))
            }
        }

        private suspend fun handleStatusResponse(
            response: Response<WidgetStatusResponse>,
            settings: WidgetSettings,
            allowCredentialRenewal: Boolean,
        ): OperationResult<WidgetFeed> =
            when {
                response.isSuccessful -> handleSuccessfulStatus(response.body())
                response.code() == 401 && allowCredentialRenewal -> {
                    sessionRepository.clearWidgetCredential()
                    refreshInternal(settings, allowCredentialRenewal = false)
                }
                else -> fail(response.toWidgetFailure(json))
            }

        private fun handleSuccessfulStatus(body: WidgetStatusResponse?): OperationResult<WidgetFeed> {
            if (body?.success != true) {
                return fail(
                    OperationResult.Failure(
                        body?.code ?: body?.error ?: "INVALID_WIDGET_RESPONSE",
                        body?.message ?: "小组件数据无效",
                    ),
                )
            }
            val feed = body.toWidgetFeed()
            WidgetFeedStore.writeSuccess(context, feed)
            _state.value =
                WidgetFeedState(
                    availability = if (feed.users.isEmpty()) WidgetAvailability.EMPTY else WidgetAvailability.READY,
                    feed = feed,
                )
            return OperationResult.Success(feed)
        }

        private fun fail(failure: OperationResult.Failure): OperationResult.Failure {
            val availability =
                when (failure.code) {
                    "NETWORK_ERROR" -> WidgetAvailability.OFFLINE
                    "HTTP_401", "HTTP_403", "MISSING_DEVICE_KEY" -> WidgetAvailability.UNAUTHORIZED
                    "HTTP_404", "HTTP_501" -> WidgetAvailability.UNAVAILABLE
                    else -> WidgetAvailability.ERROR
                }
            WidgetFeedStore.writeFailure(context, failure.code)
            _state.value =
                WidgetFeedState(
                    availability = availability,
                    feed = _state.value.feed,
                    errorCode = failure.code,
                    errorMessage = failure.message,
                )
            return failure
        }
    }

internal fun WidgetStatusResponse.toWidgetFeed(fetchedAtEpochMs: Long = System.currentTimeMillis()): WidgetFeed {
    val sourceUsers = data?.users?.takeIf { it.isNotEmpty() } ?: users
    return WidgetFeed(
        fetchedAtEpochMs = fetchedAtEpochMs,
        users = sourceUsers.mapIndexed { index, user -> user.toModel(index) },
    )
}

private fun WidgetUserDto.toModel(index: Int): WidgetUserStatus {
    val resolvedDevices =
        devices.ifEmpty {
            if (currentApp != null || device != null) {
                listOf(
                    WidgetDeviceDto(
                        deviceId = device?.deviceId,
                        deviceName = device?.deviceName.orEmpty(),
                        deviceModel = device?.deviceModel,
                        deviceType = device?.deviceType,
                        isOnline = device?.isOnline ?: isOnline,
                        userStatus = device?.userStatus ?: userStatus,
                        lastUpdate = device?.lastUpdate,
                        lastSeen = device?.lastSeen,
                        currentApp = currentApp,
                        batteryLevel = device?.batteryLevel,
                        isCharging = device?.isCharging,
                        music = music,
                        screenshotUrl = device?.screenshotUrl,
                        screenshotThumbnailUrl = device?.screenshotThumbnailUrl,
                        screenshotUpdatedAt = device?.screenshotUpdatedAt,
                    ),
                )
            } else {
                emptyList()
            }
        }
    return WidgetUserStatus(
        userId = userId.stringValue() ?: id.stringValue() ?: "user-$index",
        username = username.ifBlank { "Unknown" },
        avatarUrl = avatarUrl,
        isOnline = isOnline,
        userStatus = userStatus ?: if (isOnline) "online" else "offline",
        lastSeen = lastSeen,
        devices = resolvedDevices.mapIndexed { deviceIndex, device -> device.toModel(deviceIndex) },
    )
}

private fun WidgetDeviceDto.toModel(index: Int): WidgetDeviceStatus {
    val current = status
    return WidgetDeviceStatus(
        deviceId = deviceId.stringValue() ?: id.stringValue() ?: "device-$index",
        deviceName = resolvedDeviceName(),
        deviceType = deviceType,
        isOnline = isOnline,
        lastUpdate = lastUpdate ?: lastSeen,
        appName = current?.appName.nonBlankOr(currentApp?.appName),
        packageName = current?.packageName.nonBlankOr(currentApp?.packageName),
        appIconUrl = listOf(current?.appIconUrl, current?.iconUrl, currentApp?.appIconUrl, currentApp?.iconUrl).firstNotNullOfOrNull { it },
        batteryLevel = resolvedBatteryLevel(),
        isCharging = current?.isCharging ?: isCharging ?: false,
        userStatus = resolvedUserStatus(),
        media = listOf(current?.music, currentApp?.music, music).firstNotNullOfOrNull { it },
        screenshotUrl = current?.screenshotUrl.nonBlankOr(screenshotUrl).takeIf(String::isNotBlank),
        screenshotThumbnailUrl = current?.screenshotThumbnailUrl.nonBlankOr(screenshotThumbnailUrl).takeIf(String::isNotBlank),
        screenshotUpdatedAt = current?.screenshotUpdatedAt.nonBlankOr(screenshotUpdatedAt).takeIf(String::isNotBlank),
    )
}

private fun WidgetDeviceDto.resolvedDeviceName(): String = deviceName.ifBlank { deviceModel?.takeIf(String::isNotBlank) ?: "Android" }

private fun WidgetDeviceDto.resolvedBatteryLevel(): Int = (status?.batteryLevel ?: batteryLevel ?: 0).coerceIn(0, 100)

private fun WidgetDeviceDto.resolvedUserStatus(): String = status?.userStatus ?: userStatus ?: if (isOnline) "online" else "offline"

private fun String?.nonBlankOr(fallback: String?): String = this?.takeIf(String::isNotBlank) ?: fallback.orEmpty()

private fun JsonElement?.stringValue(): String? = (this as? JsonPrimitive)?.contentOrNull

private fun Response<*>.toWidgetFailure(json: Json): OperationResult.Failure {
    val parsed =
        runCatching {
            errorBody()?.string()?.let { json.decodeFromString<ApiErrorResponse>(it) }
        }.getOrNull()
    return WidgetResponsePolicy.failure(
        httpCode = code(),
        serverCode = parsed?.code ?: parsed?.errorCode,
        serverMessage = parsed?.message ?: parsed?.error,
        retryAfterHeader = headers()["Retry-After"],
    )
}

internal object WidgetResponsePolicy {
    fun failure(
        httpCode: Int,
        serverCode: String?,
        serverMessage: String?,
        retryAfterHeader: String?,
        nowEpochMs: Long = System.currentTimeMillis(),
    ): OperationResult.Failure {
        val isRetryable = httpCode == 429 || (httpCode in 500..599 && httpCode != 501)
        return OperationResult.Failure(
            code = if (httpCode > 0) "HTTP_$httpCode" else serverCode ?: "WIDGET_REQUEST_FAILED",
            message = serverMessage ?: serverCode ?: "请求失败 HTTP $httpCode",
            retryAfterSeconds =
                retryAfterHeader
                    ?.takeIf { httpCode == 429 }
                    ?.let { ReportResponsePolicy.parseRetryAfter(it, nowEpochMs) },
            terminal = !isRetryable,
        )
    }
}
