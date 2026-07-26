package com.nekonf.nekostatus.core.network.dto

import com.nekonf.nekostatus.core.model.MediaSnapshot
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class AuthRequest(val username: String, val password: String)

@Serializable
data class UserDto(
    val id: Long? = null,
    val username: String = "",
    val email: String? = null,
    val avatar: String? = null,
    val avatarUrl: String? = null,
)

@Serializable
data class AuthResponse(
    val success: Boolean = false,
    val token: String? = null,
    val user: UserDto? = null,
    val message: String? = null,
    val code: String? = null,
)

@Serializable
data class ProfileUpdateRequest(
    val username: String? = null,
    val email: String? = null,
    val avatar: String? = null,
    val currentPassword: String? = null,
    val newPassword: String? = null,
)

@Serializable
data class DeviceKeyRequest(
    val deviceName: String,
    val platform: String = "android",
    val deviceFingerprint: String,
)

@Serializable
data class DeviceKeyResponse(
    val success: Boolean = false,
    val deviceKey: String? = null,
    val key: String? = null,
    val deviceId: Long? = null,
    val isExisting: Boolean = false,
    val message: String? = null,
    val code: String? = null,
)

@Serializable
data class HandshakeRequest(
    val token: String,
    val model: String,
    val type: String = "android",
)

@Serializable
data class HandshakeResponse(
    val success: Boolean = false,
    val key: String? = null,
    val deviceKey: String? = null,
    val deviceId: Long? = null,
    val message: String? = null,
    val code: String? = null,
)

@Serializable
data class DeviceValidationResponse(
    val valid: Boolean = false,
    val deviceId: Long? = null,
    val deviceName: String? = null,
    val warning: String? = null,
    val warningMessage: String? = null,
    val errorCode: String? = null,
    val message: String? = null,
)

@Serializable
data class StatusPayload(
    val deviceKey: String,
    val deviceFingerprint: String,
    val clientVersion: String,
    val appVersion: String,
    val appName: String,
    val packageName: String,
    val status: String,
    val screenStatus: String,
    val batteryLevel: Int,
    val isCharging: Boolean,
    val music: MediaSnapshot? = null,
)

@Serializable
data class StatusResponse(
    val success: Boolean = false,
    val message: String? = null,
    val code: String? = null,
    val timestamp: String? = null,
    val clientVersion: String? = null,
)

@Serializable
data class WidgetTokenRequest(val deviceKey: String)

@Serializable
data class WidgetTokenResponse(
    val success: Boolean = false,
    val widgetToken: String? = null,
    val userId: JsonElement? = null,
    val username: String? = null,
    val userType: String? = null,
    val expiresAt: String? = null,
    val error: String? = null,
    val code: String? = null,
    val message: String? = null,
)

@Serializable
data class WidgetStatusResponse(
    val success: Boolean = false,
    val timestamp: String? = null,
    val data: WidgetStatusData? = null,
    val users: List<WidgetUserDto> = emptyList(),
    val error: String? = null,
    val code: String? = null,
    val message: String? = null,
)

@Serializable
data class WidgetStatusData(val users: List<WidgetUserDto> = emptyList())

@Serializable
data class WidgetUserDto(
    val userId: JsonElement? = null,
    val id: JsonElement? = null,
    val username: String = "",
    val avatarUrl: String? = null,
    val isOnline: Boolean = false,
    val userStatus: String? = null,
    val lastSeen: String? = null,
    val devices: List<WidgetDeviceDto> = emptyList(),
    val currentApp: WidgetCurrentAppDto? = null,
    val device: WidgetLegacyDeviceDto? = null,
    val music: MediaSnapshot? = null,
)

@Serializable
data class WidgetDeviceDto(
    val deviceId: JsonElement? = null,
    val id: JsonElement? = null,
    val deviceName: String = "",
    val deviceModel: String? = null,
    val deviceType: String? = null,
    val isOnline: Boolean = false,
    val userStatus: String? = null,
    val lastUpdate: String? = null,
    val lastSeen: String? = null,
    val status: WidgetDeviceStatusDto? = null,
    val currentApp: WidgetCurrentAppDto? = null,
    val batteryLevel: Int? = null,
    val isCharging: Boolean? = null,
    val music: MediaSnapshot? = null,
    val screenshotUrl: String? = null,
    val screenshotThumbnailUrl: String? = null,
    val screenshotUpdatedAt: String? = null,
)

@Serializable
data class WidgetDeviceStatusDto(
    val appName: String = "",
    val packageName: String = "",
    val appIconUrl: String? = null,
    val iconUrl: String? = null,
    val batteryLevel: Int = 0,
    val isCharging: Boolean = false,
    val userStatus: String = "offline",
    val music: MediaSnapshot? = null,
    val screenshotUrl: String? = null,
    val screenshotThumbnailUrl: String? = null,
    val screenshotUpdatedAt: String? = null,
)

@Serializable
data class WidgetCurrentAppDto(
    val appName: String = "",
    val packageName: String = "",
    val appIconUrl: String? = null,
    val iconUrl: String? = null,
    val music: MediaSnapshot? = null,
)

@Serializable
data class WidgetLegacyDeviceDto(
    val deviceId: JsonElement? = null,
    val deviceName: String = "",
    val deviceModel: String? = null,
    val deviceType: String? = null,
    val isOnline: Boolean = false,
    val userStatus: String? = null,
    val lastUpdate: String? = null,
    val lastSeen: String? = null,
    val batteryLevel: Int = 0,
    val isCharging: Boolean = false,
    val screenshotUrl: String? = null,
    val screenshotThumbnailUrl: String? = null,
    val screenshotUpdatedAt: String? = null,
)

@Serializable
data class ApiErrorResponse(
    val code: String? = null,
    val errorCode: String? = null,
    val message: String? = null,
    val error: String? = null,
)
