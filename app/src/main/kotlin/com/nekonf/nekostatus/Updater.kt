package com.nekonf.nekostatus

import android.app.DownloadManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.nekonf.nekostatus.core.data.SettingsRepository
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

@Suppress("TooManyFunctions")
object UpdateManager {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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
        val ready =
            verified &&
                readySizeValid &&
                readyVersionCode > BuildConfig.VERSION_CODE
        if (ready) {
            _uiState.value =
                UpdateUiState(
                    status = UpdateStatus.READY,
                    version = prefs.getString(KEY_VERSION, null),
                    releaseUrl = prefs.getString(KEY_RELEASE_URL, null),
                    lastCheckedEpochMs = checkedAt,
                    downloadProgressPercent = 100,
                )
            return
        }

        val downloadId = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        if (!verified && downloadId >= 0L && readyVersionCode > BuildConfig.VERSION_CODE) {
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

        if (prefs.contains(KEY_DOWNLOAD_ID) || prefs.contains(KEY_PATH)) clearDownloadedUpdate(context)
        _uiState.value = UpdateUiState(lastCheckedEpochMs = checkedAt)
    }

    fun checkNow(context: Context) {
        scope.launch {
            val settings = settingsRepository(context).updateSettings.first()
            performCheck(context.applicationContext, settings, interactive = true)
        }
    }

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
        val lastCheck = context.updatePreferences().getLong(KEY_LAST_CHECKED, 0L)
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
        checkMutex.withLock {
            if (interactive) {
                _uiState.value =
                    _uiState.value.copy(
                        status = UpdateStatus.CHECKING,
                        message = null,
                        downloadProgressPercent = null,
                    )
            }
            runCatching {
                val repository = requireNotNull(settings.activeRepository) { "Select a valid update repository" }
                val update = client.checkLatest(repository, BuildConfig.VERSION_CODE.toLong(), BuildConfig.UPDATE_PACKAGE_NAME)
                if (!repository.equals(activeRepository, ignoreCase = true)) return@runCatching
                val checkedAt = System.currentTimeMillis()
                context.updatePreferences().edit().putLong(KEY_LAST_CHECKED, checkedAt).apply()
                if (update == null) {
                    clearDownloadedUpdate(context)
                    availableUpdate = null
                    _uiState.value =
                        UpdateUiState(
                            status = UpdateStatus.LATEST,
                            lastCheckedEpochMs = checkedAt,
                        )
                } else {
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
                        enqueueDownload(context, update)
                    } else {
                        postUpdateAvailable(context, update)
                    }
                }
            }.fold(
                onSuccess = { true },
                onFailure = { error ->
                    if (interactive) {
                        _uiState.value =
                            _uiState.value.copy(
                                status = UpdateStatus.ERROR,
                                message = error.toUserMessage(),
                                downloadProgressPercent = null,
                            )
                    }
                    false
                },
            )
        }

    fun downloadAvailableUpdate(context: Context): Boolean {
        val update = availableUpdate ?: return false
        return runCatching { enqueueDownload(context, update) }
            .fold(
                onSuccess = { true },
                onFailure = {
                    reportVerificationFailure(it)
                    false
                },
            )
    }

    @Synchronized
    fun enqueueDownload(
        context: Context,
        info: UpdateInfo,
    ) {
        require(isSafeGitHubReleaseAsset(info.repository, info.apkUrl)) { "Unsafe update URL" }
        require(info.apkSizeBytes in 1..MAX_APK_BYTES) { "Invalid update size" }
        val appContext = context.applicationContext
        val prefs = appContext.updatePreferences()
        if (resumeMatchingDownload(appContext, info)) return

        clearDownloadedUpdate(appContext)
        availableUpdate = info
        val safeVersion = info.version.replace(Regex("[^A-Za-z0-9._-]"), "-")
        val fileName = "neko-status-$safeVersion.apk"
        val target = File(appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), fileName)
        if (target.exists()) target.delete()
        val request =
            DownloadManager.Request(Uri.parse(info.apkUrl))
                .setTitle(appContext.getString(R.string.update_download_title, info.version))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setAllowedOverMetered(true)
                .setDestinationInExternalFilesDir(appContext, Environment.DIRECTORY_DOWNLOADS, fileName)
        val id = appContext.getSystemService(DownloadManager::class.java).enqueue(request)
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
                downloadProgressPercent = 0,
            )
        monitorDownload(appContext, id)
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
        if (sameRelease) {
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
                        )
                    monitorDownload(context, existingId)
                    return true
                }
                DownloadManager.STATUS_SUCCESSFUL -> {
                    enqueueVerification(context, existingId)
                    return true
                }
            }
        }
        return false
    }

    private fun monitorDownload(
        context: Context,
        id: Long,
    ) {
        scope.launch {
            val manager = context.getSystemService(DownloadManager::class.java)
            while (true) {
                val snapshot = manager.snapshot(id)
                if (snapshot == null || !snapshot.status.isDownloadInProgress()) {
                    return@launch
                }
                val percent =
                    if (snapshot.totalBytes > 0L) {
                        ((snapshot.downloadedBytes * 100L) / snapshot.totalBytes).toInt().coerceIn(0, 99)
                    } else {
                        null
                    }
                _uiState.value = _uiState.value.copy(downloadProgressPercent = percent)
                delay(750)
            }
        }
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
                    clearDownloadedUpdate(context)
                    reportVerificationFailure(IllegalStateException("Update download did not complete"))
                }
            }
        }
    }

    suspend fun verifyDownload(
        context: Context,
        downloadId: Long,
    ): Result<File> =
        withContext(Dispatchers.IO) {
            verificationMutex.withLock {
                val prefs = context.updatePreferences()
                val expectedDownloadId = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
                runCatching {
                    require(downloadId == expectedDownloadId) { "Unexpected download" }
                    val file = File(requireNotNull(prefs.getString(KEY_PATH, null)))
                    require(file.isFile) { "Downloaded APK is missing" }
                    requireDownloadedFileSize(
                        actualBytes = file.length(),
                        expectedBytes = prefs.getLong(KEY_SIZE, -1L),
                    )
                    val expectedSha = requireDigest(requireNotNull(prefs.getString(KEY_SHA256, null)), "APK SHA-256")
                    require(file.sha256() == expectedSha) { "APK SHA-256 mismatch" }

                    val archive =
                        context.packageManager.getArchivePackageInfoWithSigning(file.absolutePath)
                            ?: error("Invalid APK")
                    val expectedPackage = requireNotNull(prefs.getString(KEY_PACKAGE, null))
                    val expectedVersion = requireNotNull(prefs.getString(KEY_VERSION, null))
                    val expectedVersionCode = prefs.getLong(KEY_VERSION_CODE, -1L)
                    require(expectedVersionCode > BuildConfig.VERSION_CODE) { "Update is not newer than the installed version" }
                    require(archive.packageName == expectedPackage && expectedPackage == BuildConfig.UPDATE_PACKAGE_NAME) {
                        "Unexpected package name"
                    }
                    require(archive.longVersionCode == expectedVersionCode) { "APK version code does not match update manifest" }
                    require(archive.versionName == expectedVersion) { "APK version name does not match update manifest" }

                    val archiveDigests = archive.signingDigests()
                    val installed = context.packageManager.getPackageInfoWithSigning(context.packageName)
                    val installedDigests = installed.signingDigests()
                    require(archiveDigests == installedDigests) {
                        "APK signing certificate does not match installed app"
                    }
                    val expectedCertificate =
                        requireDigest(requireNotNull(prefs.getString(KEY_CERTIFICATE, null)), "certificate SHA-256")
                    require(expectedCertificate in archiveDigests) { "APK certificate does not match update manifest" }
                    if (prefs.getString(KEY_REPOSITORY, null).equals(UpdateSettings.OFFICIAL_REPOSITORY, ignoreCase = true)) {
                        BuildConfig.OFFICIAL_CERTIFICATE_SHA256.takeIf(String::isNotBlank)?.let { official ->
                            require(requireDigest(official, "official certificate SHA-256") in archiveDigests) {
                                "APK is not signed by the official certificate"
                            }
                        }
                    }
                    prefs.edit().putBoolean(KEY_VERIFIED, true).apply()
                    _uiState.value =
                        _uiState.value.copy(
                            status = UpdateStatus.READY,
                            version = expectedVersion,
                            releaseUrl = prefs.getString(KEY_RELEASE_URL, null),
                            downloadProgressPercent = 100,
                            message = null,
                        )
                    file
                }.onFailure {
                    if (downloadId == context.updatePreferences().getLong(KEY_DOWNLOAD_ID, -1L)) {
                        clearDownloadedUpdate(context)
                    }
                }
            }
        }

    fun installReadyUpdate(context: Context): Boolean {
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
        if (
            !prefs.getBoolean(KEY_VERIFIED, false) ||
            !sizeValid ||
            versionCode <= BuildConfig.VERSION_CODE
        ) {
            clearDownloadedUpdate(context)
            _uiState.value = UpdateUiState(lastCheckedEpochMs = prefs.getLong(KEY_LAST_CHECKED, 0L).takeIf { it > 0L })
            return false
        }
        val intent =
            if (context.packageManager.canRequestPackageInstalls()) {
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, APK_MIME_TYPE)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            } else {
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        context.startActivity(intent)
        return true
    }

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

    internal fun reportVerificationFailure(error: Throwable) {
        _uiState.value =
            _uiState.value.copy(
                status = UpdateStatus.ERROR,
                message = error.toUserMessage(),
                downloadProgressPercent = null,
            )
    }

    internal fun enqueueVerification(
        context: Context,
        downloadId: Long,
    ) {
        if (downloadId < 0L || !isExpectedDownload(context, downloadId)) return
        val request =
            OneTimeWorkRequestBuilder<UpdateVerificationWorker>()
                .setInputData(workDataOf(WORK_INPUT_DOWNLOAD_ID to downloadId))
                .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            VERIFY_WORK,
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    internal fun isExpectedDownload(
        context: Context,
        downloadId: Long,
    ): Boolean = context.updatePreferences().getLong(KEY_DOWNLOAD_ID, -1L) == downloadId

    private fun clearDownloadedUpdate(context: Context) {
        val prefs = context.updatePreferences()
        prefs.getLong(KEY_DOWNLOAD_ID, -1L).takeIf { it >= 0L }?.let { id ->
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
            .apply()
        context.getSystemService(NotificationManager::class.java).apply {
            cancel(UPDATE_AVAILABLE_ID)
            cancel(UPDATE_READY_ID)
        }
        runCatching { WorkManager.getInstance(context).cancelUniqueWork(VERIFY_WORK) }
        availableUpdate = null
    }

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
    private const val KEY_LAST_CHECKED = "last_checked"
    private const val KEY_ACTIVE_REPOSITORY = "active_repository"
    private const val UPDATE_CHANNEL = "neko-status-updates"
    private const val UPDATE_AVAILABLE_ID = 4101
    private const val UPDATE_READY_ID = 4103
    private const val EXTRA_UPDATE = "update"
    private const val PAYLOAD_SEPARATOR = "|"
    private const val CHECK_INTERVAL_MILLIS = 24 * 60 * 60 * 1_000L
}

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
            onSuccess = {
                UpdateManager.postInstallNotification(applicationContext)
                Result.success()
            },
            onFailure = {
                UpdateManager.reportVerificationFailure(it)
                Result.failure()
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
                    .onFailure(UpdateManager::reportVerificationFailure)
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

private fun Throwable.toUserMessage(): String =
    when (this) {
        is IOException -> "Unable to reach GitHub. Check the network and try again."
        else -> message?.take(180) ?: "Unable to check for updates."
    }

private const val UPDATE_MANIFEST_NAME = "update.json"
private const val APK_MIME_TYPE = "application/vnd.android.package-archive"
internal const val MAX_APK_BYTES = 262_144_000L
internal const val MAX_VERSION_CHARS = 80
internal const val MAX_APK_ASSET_NAME_CHARS = 160
private const val MAX_RELEASE_NOTES_CHARS = 16_000
private val APK_ASSET_NAME_REGEX = Regex("^[A-Za-z0-9._-]+\\.apk$")
private val SHA256_REGEX = Regex("^[0-9a-f]{64}$")
