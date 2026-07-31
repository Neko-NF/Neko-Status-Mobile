package com.nekonf.nekostatus.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class ScreenState {
    @SerialName("on")
    ON,

    @SerialName("locked")
    LOCKED,

    @SerialName("off")
    OFF,
}

@Serializable
enum class PresenceState {
    @SerialName("online")
    ONLINE,

    @SerialName("away")
    AWAY,

    @SerialName("offline")
    OFFLINE,
}

fun ScreenState.toPresenceState(): PresenceState =
    when (this) {
        ScreenState.ON -> PresenceState.ONLINE
        ScreenState.LOCKED -> PresenceState.AWAY
        ScreenState.OFF -> PresenceState.OFFLINE
    }

@Serializable
data class MediaSnapshot(
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val packageName: String? = null,
    val isPlaying: Boolean = true,
)

@Serializable
data class DeviceSnapshot(
    val installationId: String,
    val appName: String,
    val packageName: String,
    val batteryLevel: Int,
    val isCharging: Boolean,
    val screenState: ScreenState,
    val media: MediaSnapshot? = null,
    val capturedAtEpochMs: Long,
) {
    val presenceState: PresenceState get() = screenState.toPresenceState()
}

@Serializable
data class UserProfile(
    val id: Long? = null,
    val username: String,
    val email: String? = null,
    val avatarUrl: String? = null,
)

@Serializable
data class AuthSession(
    val token: String,
    val user: UserProfile,
)

data class ProfileUpdate(
    val username: String? = null,
    val email: String? = null,
    val avatar: String? = null,
    val currentPassword: String? = null,
    val newPassword: String? = null,
)

@Serializable
data class DeviceCredential(
    val deviceKey: String,
    val deviceId: Long? = null,
    val deviceName: String? = null,
)

@Serializable
data class WidgetCredential(
    val token: String,
    val userId: String? = null,
    val username: String? = null,
    val userType: String? = null,
    val expiresAt: String? = null,
)

@Serializable
data class WidgetSettings(
    val enabled: Boolean = false,
    val refreshIntervalMinutes: Int = 15,
    val displayMode: WidgetDisplayMode = WidgetDisplayMode.ALL,
    val targetUserId: String? = null,
    val targetDeviceId: String? = null,
    val selectedDeviceIds: List<String> = emptyList(),
    val showDeviceSwitcher: Boolean = true,
    val theme: WidgetTheme = WidgetTheme.SYSTEM,
    val backgroundOpacityPercent: Int = 90,
    val showMusic: Boolean = true,
    val showIcons: Boolean = true,
    val showScreenshot: Boolean = false,
)

@Serializable
enum class WidgetDisplayMode { ALL, SINGLE }

@Serializable
enum class WidgetTheme { SYSTEM, LIGHT, DARK }

@Serializable
data class WidgetFeed(
    val fetchedAtEpochMs: Long,
    val users: List<WidgetUserStatus>,
)

@Serializable
data class WidgetUserStatus(
    val userId: String,
    val username: String,
    val avatarUrl: String? = null,
    val isOnline: Boolean = false,
    val userStatus: String? = null,
    val lastSeen: String? = null,
    val devices: List<WidgetDeviceStatus> = emptyList(),
)

@Serializable
data class WidgetDeviceStatus(
    val deviceId: String,
    val deviceName: String,
    val deviceType: String? = null,
    val isOnline: Boolean = false,
    val lastUpdate: String? = null,
    val appName: String = "",
    val packageName: String = "",
    val appIconUrl: String? = null,
    val batteryLevel: Int = 0,
    val isCharging: Boolean = false,
    val userStatus: String = "offline",
    val media: MediaSnapshot? = null,
    val screenshotUrl: String? = null,
    val screenshotThumbnailUrl: String? = null,
    val screenshotUpdatedAt: String? = null,
)

enum class WidgetAvailability { DISABLED, LOADING, READY, EMPTY, UNAUTHORIZED, UNAVAILABLE, OFFLINE, ERROR }

data class WidgetFeedState(
    val availability: WidgetAvailability = WidgetAvailability.DISABLED,
    val feed: WidgetFeed? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
)

@Serializable
data class ServerConfig(
    val productionUrl: String = DEFAULT_PRODUCTION_URL,
    val localUrl: String = DEFAULT_LOCAL_URL,
    val useLocalServer: Boolean = false,
) {
    val activeUrl: String get() = (if (useLocalServer) localUrl else productionUrl).trimEnd('/')

    companion object {
        const val DEFAULT_PRODUCTION_URL = "https://nekostatus.koirin.com"
        const val DEFAULT_LOCAL_URL = "http://10.0.2.2:3000"
    }
}

@Serializable
data class ReportingSettings(
    val enabled: Boolean = false,
    val restoreAfterBoot: Boolean = false,
    val intervalSeconds: Int = 10,
    val enhancedAppDetection: Boolean = false,
    val includeMedia: Boolean = true,
    val keepAliveReminderEnabled: Boolean = false,
    val keepAliveReminderIntervalHours: Int = 6,
)

@Serializable
data class ReportingHealth(
    val isRunning: Boolean = false,
    val lastAttemptEpochMs: Long? = null,
    val lastSuccessEpochMs: Long? = null,
    val lastErrorCode: String? = null,
    val lastErrorMessage: String? = null,
    val consecutiveFailures: Int = 0,
    val currentSnapshot: DeviceSnapshot? = null,
)

@Serializable
enum class UpdateSource {
    OFFICIAL,
    CUSTOM,
}

@Serializable
data class UpdateSettings(
    val automaticChecks: Boolean = true,
    val automaticDownload: Boolean = false,
    val source: UpdateSource = UpdateSource.OFFICIAL,
    val customRepository: String = "",
) {
    val activeRepository: String?
        get() =
            when (source) {
                UpdateSource.OFFICIAL -> OFFICIAL_REPOSITORY
                UpdateSource.CUSTOM -> normalizeGitHubRepository(customRepository)
            }

    fun normalized(): UpdateSettings {
        val repository = normalizeGitHubRepository(customRepository)
        require(source != UpdateSource.CUSTOM || repository != null) {
            "Custom update repository must be a public GitHub owner/repository"
        }
        return copy(customRepository = repository.orEmpty())
    }

    companion object {
        const val OFFICIAL_REPOSITORY = "Neko-NF/Neko-Status-Mobile"
    }
}

@Serializable
enum class UpdateStatus {
    IDLE,
    CHECKING,
    LATEST,
    AVAILABLE,
    DOWNLOADING,
    VERIFYING,
    READY,
    ERROR,
}

@Serializable
enum class UpdateFailureStage {
    CHECK,
    DOWNLOAD,
    VERIFY,
}

@Serializable
data class UpdateUiState(
    val status: UpdateStatus = UpdateStatus.IDLE,
    val version: String? = null,
    val releaseUrl: String? = null,
    val message: String? = null,
    val lastCheckedEpochMs: Long? = null,
    val downloadProgressPercent: Int? = null,
    val failureStage: UpdateFailureStage? = null,
    val isRefreshing: Boolean = false,
)

fun normalizeGitHubRepository(value: String): String? {
    val candidate = value.trim().trimEnd('/')
    val withoutHost =
        when {
            GITHUB_HTTPS_PREFIX.containsMatchIn(candidate) -> candidate.replaceFirst(GITHUB_HTTPS_PREFIX, "")
            GITHUB_HOST_PREFIX.containsMatchIn(candidate) -> candidate.replaceFirst(GITHUB_HOST_PREFIX, "")
            candidate.contains("://") -> null
            else -> candidate
        }
    val normalized =
        withoutHost?.let {
            if (it.endsWith(".git", ignoreCase = true)) it.dropLast(4) else it
        }?.trimEnd('/')
    val parts = normalized?.split('/').orEmpty()
    val owner = parts.getOrNull(0).orEmpty()
    val repository = parts.getOrNull(1).orEmpty()
    val valid =
        parts.size == 2 &&
            GITHUB_OWNER.matches(owner) &&
            !owner.contains("--") &&
            GITHUB_REPOSITORY.matches(repository) &&
            repository != "." && repository != ".."
    return if (valid) "$owner/$repository" else null
}

fun isValidGitHubRepository(value: String): Boolean = normalizeGitHubRepository(value) != null

private val GITHUB_HTTPS_PREFIX = Regex("^https://(?:www\\.)?github\\.com/", RegexOption.IGNORE_CASE)
private val GITHUB_HOST_PREFIX = Regex("^(?:www\\.)?github\\.com/", RegexOption.IGNORE_CASE)
private val GITHUB_OWNER = Regex("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?$")
private val GITHUB_REPOSITORY = Regex("^[A-Za-z0-9._-]{1,100}$")

@Serializable
data class ServerCapabilities(
    val history: Boolean = false,
    val announcements: Boolean = false,
    val activity: Boolean = false,
    val multiDeviceWidget: Boolean = false,
    val checkedAtEpochMs: Long? = null,
) {
    fun isEnabled(feature: Feature): Boolean =
        when (feature) {
            Feature.HISTORY -> history
            Feature.ANNOUNCEMENTS -> announcements
            Feature.ACTIVITY -> activity
            Feature.MULTI_DEVICE_WIDGET -> multiDeviceWidget
        }
}

enum class Feature { HISTORY, ANNOUNCEMENTS, ACTIVITY, MULTI_DEVICE_WIDGET }

sealed interface OperationResult<out T> {
    data class Success<T>(val value: T) : OperationResult<T>

    data class Failure(
        val code: String,
        val message: String,
        val retryAfterSeconds: Long? = null,
        val terminal: Boolean = false,
    ) : OperationResult<Nothing>
}

sealed interface ReportOutcome {
    data class Success(val serverTimestamp: String? = null) : ReportOutcome

    data class Retryable(val code: String, val message: String, val retryAfterSeconds: Long? = null) : ReportOutcome

    data class Terminal(val code: String, val message: String) : ReportOutcome
}
