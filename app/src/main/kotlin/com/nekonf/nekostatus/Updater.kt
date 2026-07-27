package com.nekonf.nekostatus

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.nekonf.nekostatus.core.data.SettingsRepository
import com.nekonf.nekostatus.core.model.UpdateFailureStage
import com.nekonf.nekostatus.core.model.UpdateSettings
import com.nekonf.nekostatus.core.model.UpdateStatus
import com.nekonf.nekostatus.core.model.UpdateUiState
import com.nekonf.nekostatus.core.model.normalizeGitHubRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

@Serializable
internal data class GitHubAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long = 0,
)

@Serializable
internal data class GitHubRelease(
    val id: Long,
    @SerialName("tag_name") val tagName: String,
    @SerialName("html_url") val htmlUrl: String,
    val name: String? = null,
    val body: String? = null,
    val assets: List<GitHubAsset> = emptyList(),
)

@Serializable
data class UpdateManifest(
    val version: String,
    val versionCode: Long,
    val packageName: String,
    val sha256: String,
    val apkAsset: String,
    val certificateSha256: String,
)

data class UpdateInfo(
    val version: String,
    val versionCode: Long,
    val packageName: String,
    val repository: String,
    val releaseId: Long,
    val apkAsset: String,
    val apkUrl: String,
    val apkSizeBytes: Long,
    val releaseUrl: String,
    val releaseNotes: String?,
    val sha256: String,
    val certificateSha256: String,
)

internal enum class UpdateInstallResult {
    INSTALLER_STARTED,
    PERMISSION_REQUIRED,
    NOT_READY,
    INSTALLER_UNAVAILABLE,
}

private data class UpdateVerificationSnapshot(
    val downloadId: Long,
    val file: File,
    val expectedSizeBytes: Long,
    val sha256: String,
    val certificateSha256: String,
    val packageName: String,
    val version: String,
    val versionCode: Long,
    val repository: String,
)

internal class GitHubUpdateClient(
    private val client: OkHttpClient = OkHttpClient.Builder().build(),
    private val apiBaseUrl: String = "https://api.github.com",
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun checkLatest(
        repository: String,
        installedVersionCode: Long,
        expectedPackageName: String,
    ): UpdateInfo? =
        withContext(Dispatchers.IO) {
            val normalized = requireNotNull(normalizeGitHubRepository(repository)) { "Invalid GitHub repository" }
            val release = requestJson<GitHubRelease>("${apiBaseUrl.trimEnd('/')}/repos/$normalized/releases/latest", MAX_RELEASE_JSON_BYTES)
            val manifestAsset =
                release.assets.singleOrNull { it.name.equals(UPDATE_MANIFEST_NAME, ignoreCase = true) }
                    ?: error("Release must contain exactly one $UPDATE_MANIFEST_NAME")
            val manifest = requestJson<UpdateManifest>(manifestAsset.downloadUrl, MAX_MANIFEST_BYTES)
            if (manifest.versionCode <= installedVersionCode) return@withContext null

            require(manifest.version.length in 1..MAX_VERSION_CHARS && manifest.version.isNotBlank()) {
                "Update version is invalid"
            }
            require(manifest.packageName == expectedPackageName) { "Unexpected package name in update manifest" }
            require(
                manifest.apkAsset.length <= MAX_APK_ASSET_NAME_CHARS &&
                    APK_ASSET_NAME_REGEX.matches(manifest.apkAsset),
            ) { "Update APK asset name is invalid" }
            val sha256 = requireDigest(manifest.sha256, "APK SHA-256")
            val certificate = requireDigest(manifest.certificateSha256, "certificate SHA-256")
            val apkAsset =
                release.assets.singleOrNull { it.name == manifest.apkAsset }
                    ?: error("Release is missing APK asset ${manifest.apkAsset}")
            require(apkAsset.name.endsWith(".apk", ignoreCase = true)) { "Update asset is not an APK" }
            require(apkAsset.size in 1..MAX_APK_BYTES) { "Update APK size is invalid" }
            require(isSafeGitHubReleaseAsset(normalized, apkAsset.downloadUrl)) { "Update APK URL is not a GitHub Release asset" }

            UpdateInfo(
                version = manifest.version,
                versionCode = manifest.versionCode,
                packageName = manifest.packageName,
                repository = normalized,
                releaseId = release.id,
                apkAsset = apkAsset.name,
                apkUrl = apkAsset.downloadUrl,
                apkSizeBytes = apkAsset.size,
                releaseUrl = release.htmlUrl,
                releaseNotes = release.body?.trim()?.take(MAX_RELEASE_NOTES_CHARS)?.takeIf(String::isNotEmpty),
                sha256 = sha256,
                certificateSha256 = certificate,
            )
        }

    private inline fun <reified T> requestJson(
        url: String,
        maxBytes: Long,
    ): T {
        val request =
            Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "Neko-Status-Mobile/${BuildConfig.VERSION_NAME}")
                .build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub returned HTTP ${response.code}")
            val body = requireNotNull(response.body) { "GitHub returned an empty response" }
            val declaredLength = body.contentLength()
            require(declaredLength < 0 || declaredLength <= maxBytes) { "GitHub response is too large" }
            val bytes = body.bytes()
            require(bytes.size <= maxBytes) { "GitHub response is too large" }
            json.decodeFromString(bytes.toString(Charsets.UTF_8))
        }
    }

    companion object {
        private const val MAX_RELEASE_JSON_BYTES = 1_048_576L
        private const val MAX_MANIFEST_BYTES = 65_536L
    }
}

@Suppress("LargeClass", "TooManyFunctions")
object UpdateManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val operationCallbackExecutor = Executor { command -> command.run() }
    private val checkMutex = Mutex()
    private val verificationMutex = Mutex()
    private val client = GitHubUpdateClient()
    private val _uiState = MutableStateFlow(UpdateUiState())

    @Volatile private var availableUpdate: UpdateInfo? = null

    @Volatile private var activeRepository: String? = null

    val uiState: StateFlow<UpdateUiState> = _uiState.asStateFlow()

    fun restoreState(context: Context) {
        val prefs = context.updatePreferences()
        val checkedAt = prefs.getLong(KEY_LAST_CHECKED, 0L).takeIf { it > 0L }
        val readyPath = prefs.getString(KEY_PATH, null)?.let(::File)?.takeIf(File::isFile)
        val readyVersionCode = prefs.getLong(KEY_VERSION_CODE, -1L)
        val verified = prefs.getBoolean(KEY_VERIFIED, false)
        val readySizeValid =
            readyPath != null &&
                runCatching {
                    requireDownloadedFileSize(
                        actualBytes = readyPath.length(),
                        expectedBytes = prefs.getLong(KEY_SIZE, -1L),
                    )
                }.isSuccess
        val downloadId = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        when (
            resolveRestoredUpdateStatus(
                verified = verified,
                readyFileValid = readySizeValid,
                storedVersionCode = readyVersionCode,
                installedVersionCode = BuildConfig.VERSION_CODE.toLong(),
                downloadId = downloadId,
                verificationEnqueued = prefs.getBoolean(KEY_VERIFYING, false),
            )
        ) {
            UpdateStatus.READY -> {
                publishReadyState(prefs, checkedAt)
                return
            }
            UpdateStatus.VERIFYING -> {
                publishVerifyingState(prefs, checkedAt)
                enqueueVerification(context.applicationContext, downloadId)
                return
            }
            UpdateStatus.DOWNLOADING -> {
                _uiState.value =
                    UpdateUiState(
                        status = UpdateStatus.DOWNLOADING,
                        version = prefs.getString(KEY_VERSION, null),
                        releaseUrl = prefs.getString(KEY_RELEASE_URL, null),
                        lastCheckedEpochMs = checkedAt,
                    )
                resumeDownload(context.applicationContext, downloadId)
                return
            }
            else -> Unit
        }

        if (prefs.contains(KEY_DOWNLOAD_ID) || prefs.contains(KEY_PATH)) clearDownloadedUpdate(context)
        _uiState.value = UpdateUiState(lastCheckedEpochMs = checkedAt)
    }

    private fun publishReadyState(
        prefs: SharedPreferences,
        checkedAt: Long? = prefs.getLong(KEY_LAST_CHECKED, 0L).takeIf { it > 0L },
        isRefreshing: Boolean = false,
    ) {
        _uiState.value =
            UpdateUiState(
                status = UpdateStatus.READY,
                version = prefs.getString(KEY_VERSION, null),
                releaseUrl = prefs.getString(KEY_RELEASE_URL, null),
                lastCheckedEpochMs = checkedAt,
                downloadProgressPercent = 100,
                isRefreshing = isRefreshing,
            )
    }

    private fun publishVerifyingState(
        prefs: SharedPreferences,
        checkedAt: Long? = prefs.getLong(KEY_LAST_CHECKED, 0L).takeIf { it > 0L },
    ) {
        _uiState.value =
            UpdateUiState(
                status = UpdateStatus.VERIFYING,
                version = prefs.getString(KEY_VERSION, null),
                releaseUrl = prefs.getString(KEY_RELEASE_URL, null),
                lastCheckedEpochMs = checkedAt,
                downloadProgressPercent = 100,
            )
    }

    private fun SharedPreferences.hasCompleteDownloadedFile(): Boolean {
        val file = getString(KEY_PATH, null)?.let(::File)?.takeIf(File::isFile) ?: return false
        return runCatching {
            requireDownloadedFileSize(
                actualBytes = file.length(),
                expectedBytes = getLong(KEY_SIZE, -1L),
            )
        }.isSuccess
    }

    private fun SharedPreferences.hasValidReadyUpdate(): Boolean =
        getBoolean(KEY_VERIFIED, false) &&
            hasCompleteDownloadedFile() &&
            getLong(KEY_VERSION_CODE, -1L) > BuildConfig.VERSION_CODE

    private fun SharedPreferences.captureVerificationSnapshot(expectedDownloadId: Long): Result<UpdateVerificationSnapshot?> {
        val values = all.toMap()
        val currentDownloadId = values[KEY_DOWNLOAD_ID] as? Long
        if (currentDownloadId != expectedDownloadId || values[KEY_VERIFIED] == true) {
            return Result.success(null)
        }
        return runCatching {
            UpdateVerificationSnapshot(
                downloadId = expectedDownloadId,
                file = File(requireNotNull(values[KEY_PATH] as? String) { "Downloaded APK path is missing" }),
                expectedSizeBytes = requireNotNull(values[KEY_SIZE] as? Long) { "Downloaded APK size is missing" },
                sha256 = requireNotNull(values[KEY_SHA256] as? String) { "APK SHA-256 is missing" },
                certificateSha256 =
                    requireNotNull(values[KEY_CERTIFICATE] as? String) {
                        "Certificate SHA-256 is missing"
                    },
                packageName = requireNotNull(values[KEY_PACKAGE] as? String) { "Update package is missing" },
                version = requireNotNull(values[KEY_VERSION] as? String) { "Update version is missing" },
                versionCode = requireNotNull(values[KEY_VERSION_CODE] as? Long) { "Update version code is missing" },
                repository = requireNotNull(values[KEY_REPOSITORY] as? String) { "Update repository is missing" },
            )
        }
    }

    fun checkNow(context: Context) {
        scope.launch {
            val settings = settingsRepository(context).updateSettings.first()
            performCheck(context.applicationContext, settings, interactive = true)
        }
    }

    @Synchronized
    fun onSettingsChanged(
        context: Context,
        settings: UpdateSettings,
    ) {
        val repository = settings.activeRepository
        val prefs = context.updatePreferences()
        val previous = prefs.getString(KEY_ACTIVE_REPOSITORY, null)
        activeRepository = repository
        if (previous != null && !previous.equals(repository, ignoreCase = true)) {
            clearDownloadedUpdate(context)
            prefs.edit()
                .remove(KEY_LAST_CHECKED)
                .putString(KEY_ACTIVE_REPOSITORY, repository)
                .apply()
            _uiState.value = UpdateUiState()
        } else if (previous == null) {
            prefs.edit().putString(KEY_ACTIVE_REPOSITORY, repository).apply()
        }
    }

    fun checkOnLaunch(context: Context) {
        val prefs = context.updatePreferences()
        if (prefs.hasValidReadyUpdate()) return
        val lastCheck = prefs.getLong(KEY_LAST_CHECKED, 0L)
        if (System.currentTimeMillis() - lastCheck < CHECK_INTERVAL_MILLIS) return
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request =
            OneTimeWorkRequestBuilder<UpdateCheckWorker>()
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
        WorkManager.getInstance(context).enqueueUniqueWork(STARTUP_WORK, ExistingWorkPolicy.KEEP, request)
    }

    fun syncSchedule(
        context: Context,
        settings: UpdateSettings,
    ) {
        val workManager = WorkManager.getInstance(context)
        if (!settings.automaticChecks) {
            workManager.cancelUniqueWork(PERIODIC_WORK)
            workManager.cancelUniqueWork(STARTUP_WORK)
            return
        }
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val request =
            PeriodicWorkRequestBuilder<UpdateCheckWorker>(24, TimeUnit.HOURS)
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    internal suspend fun performScheduledCheck(context: Context): Boolean {
        val settings = settingsRepository(context).updateSettings.first()
        if (!settings.automaticChecks) return true
        return performCheck(context, settings, interactive = false)
    }

    internal suspend fun performCheck(
        context: Context,
        settings: UpdateSettings,
        interactive: Boolean,
    ): Boolean =
        checkMutex.withLockIfIdle {
            try {
                performCheckLocked(context, settings, interactive)
            } finally {
                if (interactive) {
                    _uiState.update { current -> current.copy(isRefreshing = false) }
                }
            }
        } ?: true

    private suspend fun performCheckLocked(
        context: Context,
        settings: UpdateSettings,
        interactive: Boolean,
    ): Boolean {
        val initialPrefs = context.updatePreferences()
        val repository = settings.activeRepository
        val preserveReady =
            shouldPreserveReadyForRepository(
                hasValidReady = initialPrefs.hasValidReadyUpdate(),
                storedRepository = initialPrefs.getString(KEY_REPOSITORY, null),
                requestedRepository = repository,
            )
        if (interactive) {
            if (preserveReady) {
                publishReadyState(initialPrefs, isRefreshing = true)
            } else {
                prepareInteractiveCheckState()
            }
        }
        return runCatching {
            val selectedRepository = requireNotNull(repository) { "Select a valid update repository" }
            val update = client.checkLatest(selectedRepository, BuildConfig.VERSION_CODE.toLong(), BuildConfig.UPDATE_PACKAGE_NAME)
            applyCheckResult(context, settings, selectedRepository, update)
        }.fold(
            onSuccess = { true },
            onFailure = { error ->
                handleCheckFailure(context, repository, error, interactive)
                false
            },
        )
    }

    @Synchronized
    private fun applyCheckResult(
        context: Context,
        settings: UpdateSettings,
        repository: String,
        update: UpdateInfo?,
    ) {
        val prefs = context.updatePreferences()
        if (!prefs.isCurrentRepository(repository)) return
        val checkedAt = System.currentTimeMillis()
        prefs.edit().putLong(KEY_LAST_CHECKED, checkedAt).apply()
        val keepReady =
            shouldKeepReadyAfterCheck(
                hasValidReady =
                    shouldPreserveReadyForRepository(
                        hasValidReady = prefs.hasValidReadyUpdate(),
                        storedRepository = prefs.getString(KEY_REPOSITORY, null),
                        requestedRepository = repository,
                    ),
                readyVersionCode = prefs.getLong(KEY_VERSION_CODE, -1L),
                availableVersionCode = update?.versionCode,
            )
        when {
            keepReady -> {
                availableUpdate = null
                publishReadyState(prefs, checkedAt)
            }
            update == null -> {
                clearDownloadedUpdate(context)
                availableUpdate = null
                _uiState.value =
                    UpdateUiState(
                        status = UpdateStatus.LATEST,
                        lastCheckedEpochMs = checkedAt,
                    )
            }
            else -> {
                availableUpdate = update
                _uiState.value =
                    UpdateUiState(
                        status = UpdateStatus.AVAILABLE,
                        version = update.version,
                        releaseUrl = update.releaseUrl,
                        message = update.releaseNotes,
                        lastCheckedEpochMs = checkedAt,
                    )
                if (settings.automaticDownload) {
                    runCatching { enqueueDownload(context, update) }
                        .onFailure { error ->
                            handleDownloadStartFailure(context, update.repository, error)
                        }
                } else {
                    postUpdateAvailable(context, update)
                }
            }
        }
    }

    private fun prepareInteractiveCheckState() {
        _uiState.value =
            _uiState.value.copy(
                status = UpdateStatus.CHECKING,
                message = null,
                downloadProgressPercent = null,
                failureStage = null,
                isRefreshing = true,
            )
    }

    @Synchronized
    private fun handleCheckFailure(
        context: Context,
        repository: String?,
        error: Throwable,
        interactive: Boolean,
    ) {
        val prefs = context.updatePreferences()
        if (!prefs.isCurrentRepository(repository)) return
        logUpdateFailure(UpdateFailureStage.CHECK, error)
        if (!interactive) return
        if (
            shouldPreserveReadyForRepository(
                hasValidReady = prefs.hasValidReadyUpdate(),
                storedRepository = prefs.getString(KEY_REPOSITORY, null),
                requestedRepository = repository,
            )
        ) {
            publishReadyState(prefs)
        } else {
            _uiState.update { current ->
                transitionToFailure(
                    current = current,
                    stage = UpdateFailureStage.CHECK,
                    preserveReady = current.status == UpdateStatus.READY,
                )
            }
        }
    }

    fun downloadAvailableUpdate(context: Context): Boolean {
        val update = availableUpdate ?: return false
        return runCatching { enqueueDownload(context, update) }
            .fold(
                onSuccess = { it },
                onFailure = {
                    handleDownloadStartFailure(context, update.repository, it)
                    false
                },
            )
    }

    @Synchronized
    fun enqueueDownload(
        context: Context,
        info: UpdateInfo,
    ): Boolean {
        require(isSafeGitHubReleaseAsset(info.repository, info.apkUrl)) { "Unsafe update URL" }
        require(info.apkSizeBytes in 1..MAX_APK_BYTES) { "Invalid update size" }
        val appContext = context.applicationContext
        val prefs = appContext.updatePreferences()
        val configuredRepository = prefs.getString(KEY_ACTIVE_REPOSITORY, null) ?: activeRepository
        if (configuredRepository != null && !info.repository.equals(configuredRepository, ignoreCase = true)) {
            return false
        }
        if (resumeMatchingDownload(appContext, info)) return true

        val safeVersion = info.version.replace(Regex("[^A-Za-z0-9._-]"), "-")
        val fileName = "neko-status-$safeVersion-${info.versionCode}-${info.releaseId}.apk"
        val target = File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (target.exists()) target.delete()
        val request =
            DownloadManager.Request(Uri.parse(info.apkUrl))
                .setTitle(appContext.getString(R.string.update_download_title, info.version))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, fileName)
        val id = appContext.getSystemService(DownloadManager::class.java).enqueue(request)
        clearDownloadedUpdate(appContext)
        availableUpdate = info
        prefs.edit()
            .putLong(KEY_DOWNLOAD_ID, id)
            .putString(KEY_PATH, target.absolutePath)
            .putString(KEY_SHA256, info.sha256)
            .putString(KEY_CERTIFICATE, info.certificateSha256)
            .putString(KEY_PACKAGE, info.packageName)
            .putString(KEY_VERSION, info.version)
            .putLong(KEY_VERSION_CODE, info.versionCode)
            .putLong(KEY_SIZE, info.apkSizeBytes)
            .putString(KEY_REPOSITORY, info.repository)
            .putLong(KEY_RELEASE_ID, info.releaseId)
            .putString(KEY_RELEASE_URL, info.releaseUrl)
            .putBoolean(KEY_VERIFIED, false)
            .apply()
        _uiState.value =
            _uiState.value.copy(
                status = UpdateStatus.DOWNLOADING,
                version = info.version,
                releaseUrl = info.releaseUrl,
                message = null,
                downloadProgressPercent = 0,
                failureStage = null,
            )
        monitorDownload(appContext, id)
        return true
    }

    private fun resumeMatchingDownload(
        context: Context,
        info: UpdateInfo,
    ): Boolean {
        val prefs = context.updatePreferences()
        val existingId = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        val sameRelease =
            existingId >= 0L &&
                prefs.getLong(KEY_RELEASE_ID, -1L) == info.releaseId &&
                prefs.getString(KEY_REPOSITORY, null).equals(info.repository, ignoreCase = true)
        return when {
            !sameRelease -> false
            prefs.hasValidReadyUpdate() -> {
                publishReadyState(prefs)
                true
            }
            else ->
                when (context.getSystemService(DownloadManager::class.java).snapshot(existingId)?.status) {
                    DownloadManager.STATUS_PENDING,
                    DownloadManager.STATUS_RUNNING,
                    DownloadManager.STATUS_PAUSED,
                    -> {
                        _uiState.value =
                            _uiState.value.copy(
                                status = UpdateStatus.DOWNLOADING,
                                version = info.version,
                                releaseUrl = info.releaseUrl,
                                message = null,
                                failureStage = null,
                            )
                        monitorDownload(context, existingId)
                        true
                    }
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        enqueueVerification(context, existingId)
                        true
                    }
                    else -> false
                }
        }
    }

    private fun monitorDownload(
        context: Context,
        id: Long,
    ) {
        scope.launch {
            val manager = context.getSystemService(DownloadManager::class.java)
            while (true) {
                val snapshot = manager.snapshot(id)
                when {
                    snapshot?.status == DownloadManager.STATUS_SUCCESSFUL -> {
                        enqueueVerification(context, id)
                        return@launch
                    }
                    snapshot == null || !snapshot.status.isDownloadInProgress() -> {
                        resumeDownload(context, id)
                        return@launch
                    }
                }
                val percent =
                    if (snapshot.totalBytes > 0L) {
                        ((snapshot.downloadedBytes * 100L) / snapshot.totalBytes).toInt().coerceIn(0, 99)
                    } else {
                        null
                    }
                if (!publishDownloadProgressIfCurrent(context, id, percent)) return@launch
                delay(750)
            }
        }
    }

    @Synchronized
    private fun publishDownloadProgressIfCurrent(
        context: Context,
        downloadId: Long,
        percent: Int?,
    ): Boolean {
        if (!context.updatePreferences().isCurrentVerificationTask(downloadId)) return false
        _uiState.update { current -> current.copy(downloadProgressPercent = percent) }
        return true
    }

    private fun resumeDownload(
        context: Context,
        id: Long,
    ) {
        scope.launch {
            val manager = context.getSystemService(DownloadManager::class.java)
            val snapshot = manager.snapshot(id)
            when (snapshot?.status) {
                DownloadManager.STATUS_PENDING,
                DownloadManager.STATUS_RUNNING,
                DownloadManager.STATUS_PAUSED,
                -> monitorDownload(context, id)
                DownloadManager.STATUS_SUCCESSFUL -> enqueueVerification(context, id)
                else -> {
                    val prefs = context.updatePreferences()
                    if (snapshot == null && prefs.hasCompleteDownloadedFile()) {
                        enqueueVerification(context, id)
                    } else {
                        handleDownloadFailure(
                            context = context,
                            downloadId = id,
                            error = IllegalStateException("Update download did not complete"),
                        )
                    }
                }
            }
        }
    }

    @Synchronized
    internal fun handleDownloadFailure(
        context: Context,
        downloadId: Long,
        error: Throwable,
    ) {
        if (!context.updatePreferences().isCurrentVerificationTask(downloadId)) return
        clearDownloadedUpdate(context)
        reportFailure(UpdateFailureStage.DOWNLOAD, error)
    }

    suspend fun verifyDownload(
        context: Context,
        downloadId: Long,
    ): Result<File?> =
        withContext(Dispatchers.IO) {
            verificationMutex.withLock {
                val prefs = context.updatePreferences()
                val snapshot =
                    prefs.captureVerificationSnapshot(downloadId).getOrElse { error ->
                        return@withLock Result.failure(error)
                    } ?: return@withLock Result.success(null)
                runCatching {
                    require(snapshot.file.isFile) { "Downloaded APK is missing" }
                    requireDownloadedFileSize(
                        actualBytes = snapshot.file.length(),
                        expectedBytes = snapshot.expectedSizeBytes,
                    )
                    val expectedSha = requireDigest(snapshot.sha256, "APK SHA-256")
                    require(snapshot.file.sha256() == expectedSha) { "APK SHA-256 mismatch" }
                    if (!isCurrentVerificationSnapshot(context, snapshot)) return@runCatching null

                    val archive =
                        context.packageManager.getArchivePackageInfoWithSigning(snapshot.file.absolutePath)
                            ?: error("Invalid APK")
                    require(snapshot.versionCode > BuildConfig.VERSION_CODE) { "Update is not newer than the installed version" }
                    require(archive.packageName == snapshot.packageName && snapshot.packageName == BuildConfig.UPDATE_PACKAGE_NAME) {
                        "Unexpected package name"
                    }
                    require(archive.longVersionCode == snapshot.versionCode) {
                        "APK version code does not match update manifest"
                    }
                    require(archive.versionName == snapshot.version) { "APK version name does not match update manifest" }

                    val archiveDigests = archive.signingDigests()
                    val installed = context.packageManager.getPackageInfoWithSigning(context.packageName)
                    val installedDigests = installed.signingDigests()
                    require(archiveDigests == installedDigests) {
                        "APK signing certificate does not match installed app"
                    }
                    val expectedCertificate =
                        requireDigest(snapshot.certificateSha256, "certificate SHA-256")
                    require(expectedCertificate in archiveDigests) { "APK certificate does not match update manifest" }
                    if (snapshot.repository.equals(UpdateSettings.OFFICIAL_REPOSITORY, ignoreCase = true)) {
                        BuildConfig.OFFICIAL_CERTIFICATE_SHA256.takeIf(String::isNotBlank)?.let { official ->
                            require(requireDigest(official, "official certificate SHA-256") in archiveDigests) {
                                "APK is not signed by the official certificate"
                            }
                        }
                    }
                    if (!commitVerifiedUpdate(context, snapshot)) return@runCatching null
                    snapshot.file
                }
            }
        }

    private fun isCurrentVerificationSnapshot(
        context: Context,
        snapshot: UpdateVerificationSnapshot,
    ): Boolean =
        context.updatePreferences()
            .captureVerificationSnapshot(snapshot.downloadId)
            .getOrNull() == snapshot

    @Synchronized
    private fun commitVerifiedUpdate(
        context: Context,
        snapshot: UpdateVerificationSnapshot,
    ): Boolean {
        if (!isCurrentVerificationSnapshot(context, snapshot)) return false
        val prefs = context.updatePreferences()
        prefs.edit()
            .putBoolean(KEY_VERIFIED, true)
            .remove(KEY_VERIFYING)
            .apply()
        publishReadyState(prefs)
        return true
    }

    @Suppress("DEPRECATION") // ACTION_INSTALL_PACKAGE remains a useful fallback on OEM installers.
    internal fun installReadyUpdate(
        context: Context,
        resolveApkUri: (File) -> Uri = {
            FileProvider.getUriForFile(context, "${BuildConfig.APPLICATION_ID}.files", it)
        },
        launchIntent: (Intent) -> Unit = context::startActivity,
    ): UpdateInstallResult {
        val prefs = context.updatePreferences()
        val file = prefs.getString(KEY_PATH, null)?.let(::File)
        val versionCode = prefs.getLong(KEY_VERSION_CODE, -1L)
        val sizeValid =
            file?.takeIf(File::isFile)?.let {
                runCatching {
                    requireDownloadedFileSize(
                        actualBytes = it.length(),
                        expectedBytes = prefs.getLong(KEY_SIZE, -1L),
                    )
                }.isSuccess
            } == true
        return when {
            !prefs.getBoolean(KEY_VERIFIED, false) ||
                !sizeValid ||
                versionCode <= BuildConfig.VERSION_CODE -> {
                clearDownloadedUpdate(context)
                _uiState.value = UpdateUiState(lastCheckedEpochMs = prefs.getLong(KEY_LAST_CHECKED, 0L).takeIf { it > 0L })
                UpdateInstallResult.NOT_READY
            }
            !context.packageManager.canRequestPackageInstalls() -> UpdateInstallResult.PERMISSION_REQUIRED
            else -> launchPackageInstaller(requireNotNull(file), resolveApkUri, launchIntent)
        }
    }

    private fun launchPackageInstaller(
        file: File,
        resolveApkUri: (File) -> Uri,
        launchIntent: (Intent) -> Unit,
    ): UpdateInstallResult {
        val uri =
            runCatching {
                resolveApkUri(file)
            }.getOrElse {
                logInstallLaunchFailure("file-provider", it)
                return UpdateInstallResult.INSTALLER_UNAVAILABLE
            }
        val clipData = ClipData.newRawUri("Neko Status update", uri)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
        val intents =
            listOf(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK_MIME_TYPE)
                    .apply { this.clipData = clipData }
                    .addFlags(flags),
                Intent(Intent.ACTION_INSTALL_PACKAGE)
                    .setDataAndType(uri, APK_MIME_TYPE)
                    .apply { this.clipData = clipData }
                    .addFlags(flags),
            )
        return if (launchFirstSupportedIntent(intents, launchIntent)) {
            UpdateInstallResult.INSTALLER_STARTED
        } else {
            UpdateInstallResult.INSTALLER_UNAVAILABLE
        }
    }

    internal fun unknownSourceSettingsIntents(context: Context): List<Intent> =
        listOf(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ),
            Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.parse("package:${context.packageName}"),
            ),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
        )

    internal fun postInstallNotification(context: Context) {
        ensureChannel(context)
        val install = Intent(context, UpdateInstallActivity::class.java)
        val pending =
            PendingIntent.getActivity(
                context,
                4104,
                install,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat.Builder(context, UPDATE_CHANNEL)
                .setSmallIcon(R.drawable.ic_neko_monochrome)
                .setContentTitle(context.getString(R.string.update_ready_title))
                .setContentText(context.getString(R.string.update_ready_text))
                .setContentIntent(pending)
                .setAutoCancel(false)
                .build()
        context.getSystemService(NotificationManager::class.java).notify(UPDATE_READY_ID, notification)
    }

    @Synchronized
    internal fun postInstallNotificationIfReady(
        context: Context,
        downloadId: Long,
    ) {
        val prefs = context.updatePreferences()
        if (
            prefs.getLong(KEY_DOWNLOAD_ID, -1L) == downloadId &&
            prefs.hasValidReadyUpdate()
        ) {
            postInstallNotification(context)
        }
    }

    private fun postUpdateAvailable(
        context: Context,
        info: UpdateInfo,
    ) {
        ensureChannel(context)
        val download =
            Intent(context, UpdateActionReceiver::class.java).apply {
                action = UpdateActionReceiver.ACTION_DOWNLOAD
                putExtra(EXTRA_UPDATE, info.toPayload())
            }
        val downloadPending =
            PendingIntent.getBroadcast(
                context,
                4102,
                download,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val releasePending =
            PendingIntent.getActivity(
                context,
                4103,
                Intent(Intent.ACTION_VIEW, Uri.parse(info.releaseUrl)),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat.Builder(context, UPDATE_CHANNEL)
                .setSmallIcon(R.drawable.ic_neko_monochrome)
                .setContentTitle(context.getString(R.string.update_available_title, info.version))
                .setContentText(context.getString(R.string.update_available_text))
                .setContentIntent(releasePending)
                .setAutoCancel(true)
                .addAction(0, context.getString(R.string.update_download_action), downloadPending)
                .build()
        context.getSystemService(NotificationManager::class.java).notify(UPDATE_AVAILABLE_ID, notification)
    }

    private fun ensureChannel(context: Context) {
        val channel =
            NotificationChannel(
                UPDATE_CHANNEL,
                context.getString(R.string.update_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    internal fun settingsRepository(context: Context): SettingsRepository =
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            UpdateDependencies::class.java,
        ).settingsRepository()

    private fun Context.updatePreferences() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun UpdateInfo.toPayload(): String =
        listOf(
            version,
            versionCode.toString(),
            packageName,
            repository,
            releaseId.toString(),
            apkAsset,
            apkUrl,
            apkSizeBytes.toString(),
            releaseUrl,
            sha256,
            certificateSha256,
        ).joinToString(PAYLOAD_SEPARATOR) { Uri.encode(it) }

    internal fun payloadToUpdateInfo(payload: String): UpdateInfo? {
        val values = payload.split(PAYLOAD_SEPARATOR).map(Uri::decode)
        if (values.size != 11) return null
        return runCatching {
            UpdateInfo(
                version = values[0],
                versionCode = values[1].toLong(),
                packageName = values[2],
                repository = requireNotNull(normalizeGitHubRepository(values[3])),
                releaseId = values[4].toLong(),
                apkAsset = values[5],
                apkUrl = values[6],
                apkSizeBytes = values[7].toLong(),
                releaseUrl = values[8],
                releaseNotes = null,
                sha256 = requireDigest(values[9], "APK SHA-256"),
                certificateSha256 = requireDigest(values[10], "certificate SHA-256"),
            ).also { require(isSafeGitHubReleaseAsset(it.repository, it.apkUrl)) }
        }.getOrNull()
    }

    internal fun reportDownloadFailure(error: Throwable) {
        reportFailure(UpdateFailureStage.DOWNLOAD, error)
    }

    @Synchronized
    internal fun handleDownloadStartFailure(
        context: Context,
        repository: String,
        error: Throwable,
    ) {
        val prefs = context.updatePreferences()
        if (!prefs.isCurrentRepository(repository)) return
        logUpdateFailure(UpdateFailureStage.DOWNLOAD, error)
        if (
            shouldPreserveReadyForRepository(
                hasValidReady = prefs.hasValidReadyUpdate(),
                storedRepository = prefs.getString(KEY_REPOSITORY, null),
                requestedRepository = repository,
            )
        ) {
            publishReadyState(prefs)
        } else {
            _uiState.update { current ->
                transitionToFailure(
                    current = current,
                    stage = UpdateFailureStage.DOWNLOAD,
                    preserveReady = current.status == UpdateStatus.READY,
                )
            }
        }
    }

    @Synchronized
    internal fun handleVerificationFailure(
        context: Context,
        downloadId: Long,
        error: Throwable,
    ): Boolean {
        if (!context.updatePreferences().isCurrentVerificationTask(downloadId)) return false
        clearDownloadedUpdate(context)
        reportFailure(UpdateFailureStage.VERIFY, error)
        return true
    }

    @SuppressLint("ApplySharedPref")
    @Synchronized
    internal fun handleVerificationSchedulingFailure(
        context: Context,
        downloadId: Long,
        error: Throwable,
    ) {
        val prefs = context.updatePreferences()
        if (!prefs.isCurrentVerificationTask(downloadId)) return
        // Persist recovery before ERROR is observable so a process exit cannot restore a stuck VERIFYING state.
        prefs.edit().remove(KEY_VERIFYING).commit()
        reportFailure(UpdateFailureStage.VERIFY, error)
    }

    private fun reportFailure(
        stage: UpdateFailureStage,
        error: Throwable,
    ) {
        logUpdateFailure(stage, error)
        _uiState.update { current ->
            transitionToFailure(
                current = current,
                stage = stage,
                preserveReady = current.status == UpdateStatus.READY,
            )
        }
    }

    @Synchronized
    internal fun enqueueVerification(
        context: Context,
        downloadId: Long,
    ) {
        if (downloadId < 0L) return
        val prefs = context.updatePreferences()
        if (prefs.getLong(KEY_DOWNLOAD_ID, -1L) != downloadId) return
        if (prefs.getBoolean(KEY_VERIFIED, false)) {
            if (prefs.hasValidReadyUpdate()) publishReadyState(prefs)
        } else if (prepareDownloadForVerification(context, prefs, downloadId)) {
            prefs.edit().putBoolean(KEY_VERIFYING, true).apply()
            publishVerifyingState(prefs)
            val request =
                OneTimeWorkRequestBuilder<UpdateVerificationWorker>()
                    .setInputData(workDataOf(WORK_INPUT_DOWNLOAD_ID to downloadId))
                    .build()
            runCatching {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    verificationWorkName(downloadId),
                    ExistingWorkPolicy.KEEP,
                    request,
                )
            }.onSuccess { operation ->
                observeVerificationEnqueue(context.applicationContext, downloadId, operation)
            }.onFailure { error ->
                handleVerificationSchedulingFailure(context, downloadId, error)
            }
        }
    }

    private fun observeVerificationEnqueue(
        context: Context,
        downloadId: Long,
        operation: Operation,
    ) {
        val result = operation.result
        runCatching {
            result.addListener(
                {
                    runCatching { result.get() }
                        .onFailure { error ->
                            handleVerificationSchedulingFailure(context, downloadId, error)
                        }
                },
                operationCallbackExecutor,
            )
        }.onFailure { error ->
            handleVerificationSchedulingFailure(context, downloadId, error)
        }
    }

    private fun prepareDownloadForVerification(
        context: Context,
        prefs: SharedPreferences,
        downloadId: Long,
    ): Boolean {
        val snapshot = context.getSystemService(DownloadManager::class.java).snapshot(downloadId)
        return when {
            snapshot?.status == DownloadManager.STATUS_SUCCESSFUL -> true
            snapshot?.status?.isDownloadInProgress() == true -> {
                prefs.edit().remove(KEY_VERIFYING).apply()
                _uiState.update { current ->
                    current.copy(
                        status = UpdateStatus.DOWNLOADING,
                        message = null,
                        failureStage = null,
                    )
                }
                monitorDownload(context, downloadId)
                false
            }
            snapshot == null && prefs.hasCompleteDownloadedFile() -> true
            else -> {
                clearDownloadedUpdate(context)
                reportDownloadFailure(IllegalStateException("Update download did not complete"))
                false
            }
        }
    }

    internal fun isExpectedDownload(
        context: Context,
        downloadId: Long,
    ): Boolean = context.updatePreferences().isCurrentVerificationTask(downloadId)

    private fun SharedPreferences.isCurrentVerificationTask(downloadId: Long): Boolean {
        val values = all.toMap()
        return values[KEY_VERIFIED] != true &&
            values[KEY_DOWNLOAD_ID] == downloadId
    }

    private fun SharedPreferences.isCurrentRepository(repository: String?): Boolean {
        val persistedRepository = getString(KEY_ACTIVE_REPOSITORY, null)
        return if (repository == null) {
            activeRepository == null && persistedRepository == null
        } else {
            (activeRepository == null || repository.equals(activeRepository, ignoreCase = true)) &&
                (persistedRepository == null || repository.equals(persistedRepository, ignoreCase = true))
        }
    }

    @Synchronized
    private fun clearDownloadedUpdate(context: Context) {
        val prefs = context.updatePreferences()
        val downloadId = prefs.getLong(KEY_DOWNLOAD_ID, -1L).takeIf { it >= 0L }
        downloadId?.let { id ->
            runCatching { context.getSystemService(DownloadManager::class.java).remove(id) }
        }
        prefs.getString(KEY_PATH, null)?.let(::File)?.deleteUpdateFile(context)
        prefs.edit()
            .remove(KEY_DOWNLOAD_ID)
            .remove(KEY_PATH)
            .remove(KEY_SHA256)
            .remove(KEY_CERTIFICATE)
            .remove(KEY_PACKAGE)
            .remove(KEY_VERSION)
            .remove(KEY_VERSION_CODE)
            .remove(KEY_SIZE)
            .remove(KEY_REPOSITORY)
            .remove(KEY_RELEASE_ID)
            .remove(KEY_RELEASE_URL)
            .remove(KEY_VERIFIED)
            .remove(KEY_VERIFYING)
            .apply()
        context.getSystemService(NotificationManager::class.java).apply {
            cancel(UPDATE_AVAILABLE_ID)
            cancel(UPDATE_READY_ID)
        }
        downloadId?.let { id ->
            runCatching { WorkManager.getInstance(context).cancelUniqueWork(verificationWorkName(id)) }
        }
        availableUpdate = null
    }

    private fun verificationWorkName(downloadId: Long): String = "$VERIFY_WORK-$downloadId"

    private const val PERIODIC_WORK = "neko-periodic-release-check"
    private const val STARTUP_WORK = "neko-startup-release-check"
    private const val VERIFY_WORK = "neko-verify-release-download"
    internal const val WORK_INPUT_DOWNLOAD_ID = "download_id"
    private const val PREFS = "neko-update-downloads"
    private const val KEY_DOWNLOAD_ID = "download_id"
    private const val KEY_PATH = "path"
    private const val KEY_SHA256 = "sha256"
    private const val KEY_CERTIFICATE = "certificate"
    private const val KEY_PACKAGE = "package"
    private const val KEY_VERSION = "version"
    private const val KEY_VERSION_CODE = "version_code"
    private const val KEY_SIZE = "size"
    private const val KEY_REPOSITORY = "repository"
    private const val KEY_RELEASE_ID = "release_id"
    private const val KEY_RELEASE_URL = "release_url"
    private const val KEY_VERIFIED = "verified"
    private const val KEY_VERIFYING = "verifying"
    private const val KEY_LAST_CHECKED = "last_checked"
    private const val KEY_ACTIVE_REPOSITORY = "active_repository"
    private const val UPDATE_CHANNEL = "neko-status-updates"
    private const val UPDATE_AVAILABLE_ID = 4101
    private const val UPDATE_READY_ID = 4103
    private const val EXTRA_UPDATE = "update"
    private const val PAYLOAD_SEPARATOR = "|"
    private const val CHECK_INTERVAL_MILLIS = 24 * 60 * 60 * 1_000L
}

internal suspend fun <T> Mutex.withLockIfIdle(block: suspend () -> T): T? {
    if (!tryLock()) return null
    return try {
        block()
    } finally {
        unlock()
    }
}

@Suppress("LongParameterList")
internal fun resolveRestoredUpdateStatus(
    verified: Boolean,
    readyFileValid: Boolean,
    storedVersionCode: Long,
    installedVersionCode: Long,
    downloadId: Long,
    verificationEnqueued: Boolean,
): UpdateStatus {
    val newerVersion = storedVersionCode > installedVersionCode
    return when {
        verified && readyFileValid && newerVersion -> UpdateStatus.READY
        !verified && downloadId >= 0L && newerVersion && verificationEnqueued -> UpdateStatus.VERIFYING
        !verified && downloadId >= 0L && newerVersion -> UpdateStatus.DOWNLOADING
        else -> UpdateStatus.IDLE
    }
}

internal fun transitionToFailure(
    current: UpdateUiState,
    stage: UpdateFailureStage,
    preserveReady: Boolean,
): UpdateUiState =
    if (preserveReady && current.status == UpdateStatus.READY) {
        current
    } else {
        current.copy(
            status = UpdateStatus.ERROR,
            message = null,
            downloadProgressPercent = null,
            failureStage = stage,
        )
    }

internal fun shouldPreserveReadyForRepository(
    hasValidReady: Boolean,
    storedRepository: String?,
    requestedRepository: String?,
): Boolean =
    hasValidReady &&
        requestedRepository != null &&
        storedRepository.equals(requestedRepository, ignoreCase = true)

internal fun shouldKeepReadyAfterCheck(
    hasValidReady: Boolean,
    readyVersionCode: Long,
    availableVersionCode: Long?,
): Boolean =
    hasValidReady &&
        (availableVersionCode == null || availableVersionCode <= readyVersionCode)

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface UpdateDependencies {
    fun settingsRepository(): SettingsRepository
}

class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result =
        if (UpdateManager.performScheduledCheck(applicationContext)) {
            Result.success()
        } else {
            Result.retry()
        }
}

class UpdateVerificationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val downloadId = inputData.getLong(UpdateManager.WORK_INPUT_DOWNLOAD_ID, -1L)
        if (!UpdateManager.isExpectedDownload(applicationContext, downloadId)) return Result.success()
        return UpdateManager.verifyDownload(applicationContext, downloadId).fold(
            onSuccess = { file ->
                if (file != null) {
                    UpdateManager.postInstallNotificationIfReady(applicationContext, downloadId)
                }
                Result.success()
            },
            onFailure = {
                if (UpdateManager.handleVerificationFailure(applicationContext, downloadId, it)) {
                    Result.failure()
                } else {
                    Result.success()
                }
            },
        )
    }
}

class UpdateActionReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent?,
    ) {
        intent?.takeIf { it.action == ACTION_DOWNLOAD }
            ?.getStringExtra("update")
            ?.let(UpdateManager::payloadToUpdateInfo)
            ?.let { info ->
                runCatching { UpdateManager.enqueueDownload(context, info) }
                    .onFailure { error ->
                        UpdateManager.handleDownloadStartFailure(context, info.repository, error)
                    }
            }
    }

    companion object {
        const val ACTION_DOWNLOAD = "com.nekonf.nekostatus.action.DOWNLOAD_UPDATE"
    }
}

class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent?,
    ) {
        if (intent?.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
        UpdateManager.enqueueVerification(context.applicationContext, id)
    }
}

internal data class DownloadSnapshot(
    val status: Int,
    val downloadedBytes: Long,
    val totalBytes: Long,
)

private fun readDownloadSnapshot(cursor: Cursor): DownloadSnapshot? {
    if (!cursor.moveToFirst()) return null
    return DownloadSnapshot(
        status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
        downloadedBytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
        totalBytes = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
    )
}

private fun DownloadManager.snapshot(id: Long): DownloadSnapshot? =
    runCatching {
        query(DownloadManager.Query().setFilterById(id)).use(::readDownloadSnapshot)
    }.getOrNull()

private fun Int.isDownloadInProgress(): Boolean =
    this == DownloadManager.STATUS_PENDING ||
        this == DownloadManager.STATUS_RUNNING ||
        this == DownloadManager.STATUS_PAUSED

internal fun requireDigest(
    value: String,
    label: String,
): String {
    val normalized = value.lowercase(Locale.ROOT).replace(":", "").trim()
    require(SHA256_REGEX.matches(normalized)) { "$label must contain exactly 64 hexadecimal characters" }
    return normalized
}

internal fun requireDownloadedFileSize(
    actualBytes: Long,
    expectedBytes: Long,
) {
    require(expectedBytes in 1..MAX_APK_BYTES) { "Expected APK size is invalid" }
    require(actualBytes in 1..MAX_APK_BYTES) { "Downloaded APK size is invalid" }
    require(actualBytes == expectedBytes) { "Downloaded APK size does not match the release asset" }
}

internal fun isSafeGitHubReleaseAsset(
    repository: String,
    value: String,
): Boolean {
    val normalized = normalizeGitHubRepository(repository)
    val uri = runCatching { URI(value) }.getOrNull()
    val expectedPrefix = normalized?.let { "/$it/releases/download/" }
    return normalized != null &&
        uri?.scheme == "https" &&
        uri.host.equals("github.com", ignoreCase = true) &&
        uri.rawPath?.startsWith(requireNotNull(expectedPrefix), ignoreCase = true) == true
}

private fun File.sha256(): String =
    inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count <= 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest().toHex()
    }

private fun File.deleteUpdateFile(context: Context) {
    val downloads = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.canonicalFile ?: return
    val candidate = runCatching { canonicalFile }.getOrNull() ?: return
    if (candidate.parentFile == downloads) candidate.delete()
}

private fun PackageInfo.signingDigests(): Set<String> {
    return signingInfo?.apkContentsSigners.orEmpty().map { it.toByteArray().sha256() }.toSet()
}

private fun ByteArray.sha256(): String = MessageDigest.getInstance("SHA-256").digest(this).toHex()

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun PackageManager.getArchivePackageInfoWithSigning(archivePath: String): PackageInfo? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getPackageArchiveInfo(
            archivePath,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
    } else {
        @Suppress("DEPRECATION")
        getPackageArchiveInfo(archivePath, PackageManager.GET_SIGNING_CERTIFICATES)
    }

private fun PackageManager.getPackageInfoWithSigning(packageName: String): PackageInfo =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong()),
        )
    } else {
        @Suppress("DEPRECATION")
        getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
    }

internal fun launchFirstSupportedIntent(
    intents: Iterable<Intent>,
    launch: (Intent) -> Unit,
): Boolean {
    intents.forEach { intent ->
        try {
            launch(intent)
            return true
        } catch (error: RuntimeException) {
            logInstallLaunchFailure(intent.action.orEmpty(), error)
        }
    }
    return false
}

private fun logInstallLaunchFailure(
    action: String,
    error: Throwable,
) {
    Log.w(
        UPDATE_LOG_TAG,
        "install-launch action=$action category=${error.diagnosticCategory()}",
    )
}

private fun logUpdateFailure(
    stage: UpdateFailureStage,
    error: Throwable,
) {
    Log.w(UPDATE_LOG_TAG, "stage=${stage.name} category=${error.diagnosticCategory()}")
}

private fun Throwable.diagnosticCategory(): String =
    when (this) {
        is ActivityNotFoundException -> "activity-not-found"
        is IOException -> "io"
        is SecurityException -> "security"
        is IllegalArgumentException -> "invalid-data"
        is IllegalStateException -> "invalid-state"
        else -> "unexpected"
    }

private const val UPDATE_MANIFEST_NAME = "update.json"
private const val UPDATE_LOG_TAG = "NekoUpdater"
private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
internal const val MAX_APK_BYTES = 262_144_000L
internal const val MAX_VERSION_CHARS = 80
internal const val MAX_APK_ASSET_NAME_CHARS = 160
private const val MAX_RELEASE_NOTES_CHARS = 16_000
private val APK_ASSET_NAME_REGEX = Regex("^[A-Za-z0-9._-]+\\.apk$")
private val SHA256_REGEX = Regex("^[0-9a-f]{64}$")
