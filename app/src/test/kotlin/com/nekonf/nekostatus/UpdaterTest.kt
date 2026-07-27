package com.nekonf.nekostatus

import android.app.Activity
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Environment
import android.provider.Settings
import com.nekonf.nekostatus.core.model.UpdateFailureStage
import com.nekonf.nekostatus.core.model.UpdateSettings
import com.nekonf.nekostatus.core.model.UpdateSource
import com.nekonf.nekostatus.core.model.UpdateStatus
import com.nekonf.nekostatus.core.model.UpdateUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.IOException

class UpdateValidationTest {
    @Test
    fun `digest normalization accepts uppercase colon separated sha256`() {
        val digest = List(32) { "AB" }.joinToString(":")

        assertEquals("ab".repeat(32), requireDigest("  $digest  ", "APK SHA-256"))
    }

    @Test
    fun `digest validation rejects wrong length and non hexadecimal text`() {
        listOf("a".repeat(63), "a".repeat(65), "g".repeat(64)).forEach { digest ->
            val failure = runCatching { requireDigest(digest, "test digest") }.exceptionOrNull()

            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure?.message.orEmpty().contains("64 hexadecimal characters"))
        }
    }

    @Test
    fun `release asset validation only accepts matching GitHub HTTPS download paths`() {
        assertTrue(
            isSafeGitHubReleaseAsset(
                repository = "Neko-NF/Neko-Status-Mobile",
                value = "https://github.com/Neko-NF/Neko-Status-Mobile/releases/download/v2.0.0/neko-status.apk",
            ),
        )
        assertFalse(
            isSafeGitHubReleaseAsset(
                repository = "Neko-NF/Neko-Status-Mobile",
                value = "http://github.com/Neko-NF/Neko-Status-Mobile/releases/download/v2.0.0/neko-status.apk",
            ),
        )
        assertFalse(
            isSafeGitHubReleaseAsset(
                repository = "Neko-NF/Neko-Status-Mobile",
                value = "https://github.com.evil.example/Neko-NF/Neko-Status-Mobile/releases/download/v2.0.0/neko-status.apk",
            ),
        )
        assertFalse(
            isSafeGitHubReleaseAsset(
                repository = "Neko-NF/Neko-Status-Mobile",
                value = "https://github.com/other/repository/releases/download/v2.0.0/neko-status.apk",
            ),
        )
        assertFalse(
            isSafeGitHubReleaseAsset(
                repository = "not a repository",
                value = "https://github.com/Neko-NF/Neko-Status-Mobile/releases/download/v2.0.0/neko-status.apk",
            ),
        )
    }

    @Test
    fun `downloaded APK size must match the bounded release asset size`() {
        requireDownloadedFileSize(actualBytes = 1L, expectedBytes = 1L)
        requireDownloadedFileSize(actualBytes = MAX_APK_BYTES, expectedBytes = MAX_APK_BYTES)

        listOf(
            0L to 0L,
            1L to 0L,
            0L to 1L,
            MAX_APK_BYTES + 1L to MAX_APK_BYTES + 1L,
            1_024L to 2_048L,
        ).forEach { (actual, expected) ->
            assertTrue(
                runCatching { requireDownloadedFileSize(actual, expected) }.exceptionOrNull() is
                    IllegalArgumentException,
            )
        }
    }
}

class UpdateStateMachineTest {
    @Test
    fun `in flight check ignores duplicate instead of queueing it`() =
        runTest {
            val mutex = Mutex()
            val entered = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val first =
                async {
                    mutex.withLockIfIdle {
                        entered.complete(Unit)
                        release.await()
                        1
                    }
                }
            entered.await()
            var duplicateExecuted = false

            val duplicate =
                mutex.withLockIfIdle {
                    duplicateExecuted = true
                    2
                }

            assertNull(duplicate)
            assertFalse(duplicateExecuted)
            release.complete(Unit)
            assertEquals(1, first.await())
        }

    @Test
    fun `restoration distinguishes downloading verifying ready and stale states`() {
        assertEquals(
            UpdateStatus.DOWNLOADING,
            restoredStatus(verified = false, readyFileValid = false, verificationEnqueued = false),
        )
        assertEquals(
            UpdateStatus.VERIFYING,
            restoredStatus(verified = false, readyFileValid = true, verificationEnqueued = true),
        )
        assertEquals(
            UpdateStatus.READY,
            restoredStatus(verified = true, readyFileValid = true, verificationEnqueued = false),
        )
        assertEquals(
            UpdateStatus.IDLE,
            restoredStatus(verified = true, readyFileValid = false, verificationEnqueued = false),
        )
    }

    @Test
    fun `ready install entry wins over a later failure transition`() {
        val ready =
            UpdateUiState(
                status = UpdateStatus.READY,
                version = "2.0.0",
                downloadProgressPercent = 100,
            )

        val result =
            transitionToFailure(
                current = ready,
                stage = UpdateFailureStage.VERIFY,
                preserveReady = true,
            )

        assertEquals(ready, result)
    }

    @Test
    fun `failure transition exposes stable stage without exception details`() {
        val result =
            transitionToFailure(
                current =
                    UpdateUiState(
                        status = UpdateStatus.CHECKING,
                        message = "https://signed.example/private-token",
                    ),
                stage = UpdateFailureStage.CHECK,
                preserveReady = false,
            )

        assertEquals(UpdateStatus.ERROR, result.status)
        assertEquals(UpdateFailureStage.CHECK, result.failureStage)
        assertNull(result.message)
        assertNull(result.downloadProgressPercent)
    }

    @Test
    fun `ready package is retained for same repository unless a newer release exists`() {
        assertTrue(
            shouldPreserveReadyForRepository(
                hasValidReady = true,
                storedRepository = "Neko-NF/Neko-Status-Mobile",
                requestedRepository = "neko-nf/neko-status-mobile",
            ),
        )
        assertFalse(
            shouldPreserveReadyForRepository(
                hasValidReady = true,
                storedRepository = "Neko-NF/Neko-Status-Mobile",
                requestedRepository = "someone/other-updates",
            ),
        )
        assertTrue(
            shouldKeepReadyAfterCheck(
                hasValidReady = true,
                readyVersionCode = 2_000_002,
                availableVersionCode = 2_000_002,
            ),
        )
        assertTrue(
            shouldKeepReadyAfterCheck(
                hasValidReady = true,
                readyVersionCode = 2_000_002,
                availableVersionCode = null,
            ),
        )
        assertFalse(
            shouldKeepReadyAfterCheck(
                hasValidReady = true,
                readyVersionCode = 2_000_002,
                availableVersionCode = 2_000_003,
            ),
        )
    }

    private fun restoredStatus(
        verified: Boolean,
        readyFileValid: Boolean,
        verificationEnqueued: Boolean,
    ): UpdateStatus =
        resolveRestoredUpdateStatus(
            verified = verified,
            readyFileValid = readyFileValid,
            storedVersionCode = 2,
            installedVersionCode = 1,
            downloadId = 42,
            verificationEnqueued = verificationEnqueued,
        )
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UpdatePayloadTest {
    @Test
    fun `update payload round trips every field`() {
        val expected =
            UpdateInfo(
                version = "2.0.0-alpha.2",
                versionCode = 2_000_002,
                packageName = "com.nekonf.nekostatus",
                repository = "Neko-NF/Neko-Status-Mobile",
                releaseId = 42,
                apkAsset = "neko-status-v2.0.0-alpha.2.apk",
                apkUrl =
                    "https://github.com/Neko-NF/Neko-Status-Mobile/releases/download/" +
                        "v2.0.0-alpha.2/neko-status-v2.0.0-alpha.2.apk",
                apkSizeBytes = 31_457_280,
                releaseUrl = "https://github.com/Neko-NF/Neko-Status-Mobile/releases/tag/v2.0.0-alpha.2",
                releaseNotes = "Fixes spaces, percent 100%, and payload separator | safely.",
                sha256 = "ab".repeat(32),
                certificateSha256 = "cd".repeat(32),
            )
        val encoder = UpdateManager::class.java.declaredMethods.single { it.name == "toPayload" }
        encoder.isAccessible = true

        val payload = encoder.invoke(UpdateManager, expected) as String

        assertEquals(expected.copy(releaseNotes = null), UpdateManager.payloadToUpdateInfo(payload))
        assertFalse(payload.contains("Fixes spaces"))
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UpdateInstallNotificationTest {
    private val context: Application
        get() = RuntimeEnvironment.getApplication()

    @After
    fun cleanUpInstallState() {
        context.getSharedPreferences(UPDATE_PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.resolve(READY_APK)?.delete()
        shadowOf(context.packageManager).setCanRequestPackageInstalls(false)
        shadowOf(context).clearNextStartedActivities()
    }

    @Test
    fun `verified update notification launches install activity and remains available`() {
        UpdateManager.postInstallNotification(context)

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = shadowOf(manager).allNotifications.single()
        val contentIntent = requireNotNull(notification.contentIntent)
        val installIntent = shadowOf(contentIntent).savedIntent
        assertTrue(contentIntent.isActivity)
        assertEquals(UpdateInstallActivity::class.java.name, installIntent.component?.className)
        assertEquals(0, notification.flags and Notification.FLAG_AUTO_CANCEL)
    }

    @Test
    fun `download completion receiver is exported only behind the platform signature permission`() {
        val receiver =
            context.packageManager.getReceiverInfo(
                ComponentName(context, UpdateDownloadReceiver::class.java),
                PackageManager.ComponentInfoFlags.of(0),
            )

        assertTrue(receiver.exported)
        assertEquals("android.permission.SEND_DOWNLOAD_COMPLETED_INTENTS", receiver.permission)
    }

    @Test
    fun `stale verification task cannot publish install notification`() {
        context.getSharedPreferences("neko-update-downloads", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putLong("download_id", 200)
            .apply()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancelAll()

        UpdateManager.postInstallNotificationIfReady(context, downloadId = 100)

        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun `install manager launches verified content uri with read permission`() {
        val apk = prepareReadyUpdate()
        shadowOf(context.packageManager).setCanRequestPackageInstalls(true)
        val launchedIntents = mutableListOf<Intent>()
        val uri = Uri.parse("content://${BuildConfig.APPLICATION_ID}.files/updates/$READY_APK")

        val result =
            UpdateManager.installReadyUpdate(
                context = context,
                resolveApkUri = { uri },
                launchIntent = { launchedIntents += it },
            )

        assertEquals(UpdateInstallResult.INSTALLER_STARTED, result)
        val installIntent = launchedIntents.single()
        assertEquals(Intent.ACTION_VIEW, installIntent.action)
        assertEquals("application/vnd.android.package-archive", installIntent.type)
        assertEquals("content", installIntent.data?.scheme)
        assertEquals("${BuildConfig.APPLICATION_ID}.files", installIntent.data?.authority)
        assertTrue(installIntent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(installIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertEquals(installIntent.data, installIntent.clipData?.getItemAt(0)?.uri)
        assertTrue(apk.exists())
    }

    @Test
    fun `install bridge resumes installation after unknown source permission`() {
        val activity = Robolectric.buildActivity(PermissionContinuationActivity::class.java).create().get()
        val activityShadow = shadowOf(activity)
        val permissionRequest = requireNotNull(activityShadow.nextStartedActivityForResult)

        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, permissionRequest.intent.action)
        assertEquals("package:${context.packageName}", permissionRequest.intent.data.toString())
        assertFalse(activity.isFinishing)
        requireNotNull(activityShadow.nextStartedActivity)

        activityShadow.receiveResult(permissionRequest.intent, Activity.RESULT_OK, null)

        assertEquals(2, activity.installAttempts)
        assertTrue(activity.isFinishing)
    }

    @Test
    fun `installer launch falls back after an unavailable activity`() {
        val attempts = mutableListOf<String>()

        val launched =
            launchFirstSupportedIntent(
                listOf(Intent("first"), Intent("second")),
            ) { intent ->
                attempts += requireNotNull(intent.action)
                if (intent.action == "first") throw ActivityNotFoundException()
            }

        assertTrue(launched)
        assertEquals(listOf("first", "second"), attempts)
    }

    @Test
    fun `install activity finishes when no verified update exists`() {
        val activity = Robolectric.buildActivity(UpdateInstallActivity::class.java).create().get()

        assertTrue(activity.isFinishing)
    }

    private fun prepareReadyUpdate(): java.io.File {
        val apk = requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)).resolve(READY_APK)
        apk.parentFile?.mkdirs()
        apk.writeBytes(byteArrayOf(1, 2, 3))
        context.getSharedPreferences(UPDATE_PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .putString("path", apk.absolutePath)
            .putLong("size", apk.length())
            .putLong("version_code", BuildConfig.VERSION_CODE.toLong() + 1)
            .putString("version", "next")
            .putString("repository", UpdateSettings.OFFICIAL_REPOSITORY)
            .putBoolean("verified", true)
            .commit()
        return apk
    }

    private companion object {
        const val UPDATE_PREFERENCES = "neko-update-downloads"
        const val READY_APK = "ready-install-test.apk"
    }

    class PermissionContinuationActivity : UpdateInstallActivity() {
        var installAttempts = 0

        override fun installReadyUpdate(): UpdateInstallResult {
            installAttempts += 1
            return if (installAttempts == 1) {
                UpdateInstallResult.PERMISSION_REQUIRED
            } else {
                UpdateInstallResult.INSTALLER_STARTED
            }
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class UpdateRestoreStateTest {
    private val context: Context
        get() = RuntimeEnvironment.getApplication()

    @Before
    fun clearPreferences() {
        context.getSharedPreferences(UPDATE_PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        UpdateManager.onSettingsChanged(context, UpdateSettings())
        UpdateManager.restoreState(context)
    }

    @After
    fun cleanUp() {
        context.getSharedPreferences(UPDATE_PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
        context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.resolve(STALE_APK)?.delete()
    }

    @Test
    fun `installed update is removed instead of being restored as ready`() {
        val apk = requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)).resolve(STALE_APK)
        apk.parentFile?.mkdirs()
        apk.writeBytes(byteArrayOf(1, 2, 3))
        val prefs = context.getSharedPreferences(UPDATE_PREFERENCES, Context.MODE_PRIVATE)
        prefs.edit()
            .putString("path", apk.absolutePath)
            .putLong("size", apk.length())
            .putLong("version_code", BuildConfig.VERSION_CODE.toLong())
            .putBoolean("verified", true)
            .commit()

        UpdateManager.restoreState(context)

        assertEquals(UpdateStatus.IDLE, UpdateManager.uiState.value.status)
        assertFalse(apk.exists())
        assertFalse(prefs.contains("path"))
        assertFalse(prefs.contains("verified"))
    }

    @Test
    fun `valid ready update survives later task failures`() {
        val apk = requireNotNull(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)).resolve(STALE_APK)
        apk.parentFile?.mkdirs()
        apk.writeBytes(byteArrayOf(1, 2, 3))
        val prefs = context.getSharedPreferences(UPDATE_PREFERENCES, Context.MODE_PRIVATE)
        prefs.edit()
            .putString("path", apk.absolutePath)
            .putLong("size", apk.length())
            .putLong("version_code", BuildConfig.VERSION_CODE.toLong() + 1)
            .putString("version", "next")
            .putString("repository", UpdateSettings.OFFICIAL_REPOSITORY)
            .putBoolean("verified", true)
            .commit()

        UpdateManager.restoreState(context)
        UpdateManager.handleDownloadStartFailure(
            context = context,
            repository = UpdateSettings.OFFICIAL_REPOSITORY,
            error = IOException("new download could not start"),
        )
        val handled =
            UpdateManager.handleVerificationFailure(
                context = context,
                downloadId = 42,
                error = IOException("private diagnostic detail"),
            )

        assertFalse(handled)
        assertEquals(UpdateStatus.READY, UpdateManager.uiState.value.status)
        assertNull(UpdateManager.uiState.value.failureStage)
        assertNull(UpdateManager.uiState.value.message)
        assertTrue(apk.exists())
        assertTrue(prefs.getBoolean("verified", false))
    }

    @Test
    fun `verified download is not eligible for duplicate verification work`() {
        val prefs = context.getSharedPreferences(UPDATE_PREFERENCES, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("download_id", 42)
            .putBoolean("verified", false)
            .commit()
        assertTrue(UpdateManager.isExpectedDownload(context, 42))

        prefs.edit().putBoolean("verified", true).commit()

        assertFalse(UpdateManager.isExpectedDownload(context, 42))
    }

    @Test
    fun `stale verification silently leaves replacement download untouched`() =
        runTest {
            val prefs = context.getSharedPreferences(UPDATE_PREFERENCES, Context.MODE_PRIVATE)
            prefs.edit()
                .putLong("download_id", 200)
                .putBoolean("verified", false)
                .commit()
            val stateBefore = UpdateManager.uiState.value

            UpdateManager.handleDownloadFailure(
                context = context,
                downloadId = 100,
                error = IOException("old download failed"),
            )
            val verification = UpdateManager.verifyDownload(context, downloadId = 100)
            val handled =
                UpdateManager.handleVerificationFailure(
                    context = context,
                    downloadId = 100,
                    error = IOException("old worker failed"),
                )

            assertTrue(verification.isSuccess)
            assertNull(verification.getOrNull())
            assertFalse(handled)
            assertEquals(200L, prefs.getLong("download_id", -1L))
            assertFalse(prefs.getBoolean("verified", false))
            assertEquals(stateBefore, UpdateManager.uiState.value)
        }

    @Test
    fun `verification enqueue failure only affects its current download`() {
        val prefs = context.getSharedPreferences(UPDATE_PREFERENCES, Context.MODE_PRIVATE)
        prefs.edit()
            .putLong("download_id", 200)
            .putBoolean("verified", false)
            .putBoolean("verifying", true)
            .commit()
        val stateBefore = UpdateManager.uiState.value

        UpdateManager.handleVerificationSchedulingFailure(
            context = context,
            downloadId = 100,
            error = IOException("old enqueue failed"),
        )

        assertTrue(prefs.getBoolean("verifying", false))
        assertEquals(stateBefore, UpdateManager.uiState.value)

        UpdateManager.handleVerificationSchedulingFailure(
            context = context,
            downloadId = 200,
            error = IOException("current enqueue failed"),
        )

        assertFalse(prefs.contains("verifying"))
        assertEquals(UpdateStatus.ERROR, UpdateManager.uiState.value.status)
        assertEquals(UpdateFailureStage.VERIFY, UpdateManager.uiState.value.failureStage)
    }

    @Test
    fun `interactive check clears refresh feedback after validation failure`() =
        runTest {
            val settings =
                UpdateSettings(
                    source = UpdateSource.CUSTOM,
                    customRepository = "invalid",
                )
            UpdateManager.onSettingsChanged(context, settings)

            val successful = UpdateManager.performCheck(context, settings, interactive = true)

            assertFalse(successful)
            assertFalse(UpdateManager.uiState.value.isRefreshing)
            assertEquals(UpdateStatus.ERROR, UpdateManager.uiState.value.status)
            assertEquals(UpdateFailureStage.CHECK, UpdateManager.uiState.value.failureStage)
        }

    @Test
    fun `download action from previous repository is ignored after source switch`() {
        UpdateManager.onSettingsChanged(
            context,
            UpdateSettings(
                source = UpdateSource.CUSTOM,
                customRepository = "someone/new-updates",
            ),
        )
        val oldRepository = "someone/old-updates"
        val oldUpdate =
            UpdateInfo(
                version = "2.0.1",
                versionCode = BuildConfig.VERSION_CODE.toLong() + 1,
                packageName = BuildConfig.UPDATE_PACKAGE_NAME,
                repository = oldRepository,
                releaseId = 42,
                apkAsset = "neko-status.apk",
                apkUrl = "https://github.com/$oldRepository/releases/download/v2.0.1/neko-status.apk",
                apkSizeBytes = 1_024,
                releaseUrl = "https://github.com/$oldRepository/releases/tag/v2.0.1",
                releaseNotes = null,
                sha256 = "ab".repeat(32),
                certificateSha256 = "cd".repeat(32),
            )
        val stateBefore = UpdateManager.uiState.value

        val enqueued = UpdateManager.enqueueDownload(context, oldUpdate)
        UpdateManager.handleDownloadStartFailure(
            context = context,
            repository = oldRepository,
            error = IOException("old source failed"),
        )

        assertFalse(enqueued)
        assertEquals(stateBefore, UpdateManager.uiState.value)
        assertFalse(
            context.getSharedPreferences(UPDATE_PREFERENCES, Context.MODE_PRIVATE)
                .contains("download_id"),
        )
    }

    private companion object {
        const val UPDATE_PREFERENCES = "neko-update-downloads"
        const val STALE_APK = "stale-update.apk"
    }
}

class GitHubUpdateClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: GitHubUpdateClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = GitHubUpdateClient(apiBaseUrl = server.url("/").toString())
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `latest release returns a verified update model`() =
        runTest {
            enqueueRelease()

            val update =
                client.checkLatest(
                    repository = OFFICIAL_REPOSITORY,
                    installedVersionCode = 2_000_001,
                    expectedPackageName = PACKAGE_NAME,
                )

            assertNotNull(update)
            assertEquals("2.0.0-alpha.2", update?.version)
            assertEquals(2_000_002L, update?.versionCode)
            assertEquals(OFFICIAL_REPOSITORY, update?.repository)
            assertEquals("neko-status.apk", update?.apkAsset)
            assertEquals(APK_SIZE, update?.apkSizeBytes)
            assertEquals("ab".repeat(32), update?.sha256)
            assertEquals("cd".repeat(32), update?.certificateSha256)
            assertEquals("Release notes", update?.releaseNotes)
            assertEquals("/repos/$OFFICIAL_REPOSITORY/releases/latest", server.takeRequest().path)
            assertEquals("/assets/update.json", server.takeRequest().path)
        }

    @Test
    fun `custom repository is normalized into GitHub API request path`() =
        runTest {
            val repository = "someone/Custom-Updates"
            enqueueRelease(repository = repository)

            val update =
                client.checkLatest(
                    repository = "https://github.com/$repository/",
                    installedVersionCode = 1,
                    expectedPackageName = PACKAGE_NAME,
                )

            assertEquals(repository, update?.repository)
            assertEquals("/repos/$repository/releases/latest", server.takeRequest().path)
            assertEquals("/assets/update.json", server.takeRequest().path)
        }

    @Test
    fun `current version returns no update`() =
        runTest {
            enqueueRelease(versionCode = 2_000_002)

            val update =
                client.checkLatest(
                    repository = OFFICIAL_REPOSITORY,
                    installedVersionCode = 2_000_002,
                    expectedPackageName = PACKAGE_NAME,
                )

            assertNull(update)
        }

    @Test
    fun `release without update manifest is rejected`() =
        runTest {
            enqueueRelease(includeManifest = false)

            val failure = checkFailure()

            assertTrue(failure is IllegalStateException)
            assertTrue(failure.message.orEmpty().contains("exactly one update.json"))
        }

    @Test
    fun `invalid manifest digest is rejected`() =
        runTest {
            enqueueRelease(sha256 = "not-a-sha256")

            val failure = checkFailure()

            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure.message.orEmpty().contains("APK SHA-256"))
        }

    @Test
    fun `manifest rejects oversized version and unsafe APK asset names`() =
        runTest {
            enqueueRelease(version = "v".repeat(MAX_VERSION_CHARS + 1))
            assertTrue(checkFailure().message.orEmpty().contains("version is invalid"))

            enqueueRelease(apkAsset = "../neko-status.apk")
            assertTrue(checkFailure().message.orEmpty().contains("asset name is invalid"))

            val longAsset = "a".repeat(MAX_APK_ASSET_NAME_CHARS) + ".apk"
            enqueueRelease(apkAsset = longAsset)
            assertTrue(checkFailure().message.orEmpty().contains("asset name is invalid"))
        }

    @Test
    fun `APK outside matching GitHub release is rejected`() =
        runTest {
            enqueueRelease(apkUrl = "https://downloads.example.com/neko-status.apk")

            val failure = checkFailure()

            assertTrue(failure is IllegalArgumentException)
            assertTrue(failure.message.orEmpty().contains("not a GitHub Release asset"))
        }

    @Test
    fun `empty and oversized APK assets are rejected`() =
        runTest {
            listOf(0L, MAX_APK_BYTES + 1).forEach { invalidSize ->
                enqueueRelease(apkSize = invalidSize)

                val failure = checkFailure()

                assertTrue(failure is IllegalArgumentException)
                assertTrue(failure.message.orEmpty().contains("APK size is invalid"))
            }
        }

    @Test
    fun `GitHub HTTP failure is surfaced as an IO error`() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(404))

            val failure = checkFailure()

            assertTrue(failure is IOException)
            assertEquals("GitHub returned HTTP 404", failure.message)
        }

    private suspend fun checkFailure(): Throwable {
        val result =
            runCatching {
                client.checkLatest(
                    repository = OFFICIAL_REPOSITORY,
                    installedVersionCode = 2_000_001,
                    expectedPackageName = PACKAGE_NAME,
                )
            }
        return requireNotNull(result.exceptionOrNull())
    }

    @Suppress("LongParameterList")
    private fun enqueueRelease(
        repository: String = OFFICIAL_REPOSITORY,
        version: String = "2.0.0-alpha.2",
        versionCode: Long = 2_000_002,
        sha256: String = "ab".repeat(32),
        apkAsset: String = "neko-status.apk",
        apkUrl: String = "https://github.com/$repository/releases/download/v2.0.0-alpha.2/neko-status.apk",
        apkSize: Long = APK_SIZE,
        includeManifest: Boolean = true,
    ) {
        val manifestAsset =
            if (includeManifest) {
                """
                ,{
                  "name": "update.json",
                  "browser_download_url": "${server.url("/assets/update.json")}",
                  "size": 512
                }
                """.trimIndent()
            } else {
                ""
            }
        server.enqueue(
            jsonResponse(
                """
                {
                  "id": 42,
                  "tag_name": "v2.0.0-alpha.2",
                  "html_url": "https://github.com/$repository/releases/tag/v2.0.0-alpha.2",
                  "body": "  Release notes  ",
                  "assets": [
                    {
                      "name": "$apkAsset",
                      "browser_download_url": "$apkUrl",
                      "size": $apkSize
                    }
                    $manifestAsset
                  ]
                }
                """.trimIndent(),
            ),
        )
        if (includeManifest) {
            server.enqueue(
                jsonResponse(
                    """
                    {
                      "version": "$version",
                      "versionCode": $versionCode,
                      "packageName": "$PACKAGE_NAME",
                      "sha256": "$sha256",
                      "apkAsset": "$apkAsset",
                      "certificateSha256": "${"cd".repeat(32)}"
                    }
                    """.trimIndent(),
                ),
            )
        }
    }

    private fun jsonResponse(body: String): MockResponse =
        MockResponse()
            .setHeader("Content-Type", "application/json")
            .setBody(body)

    private companion object {
        const val OFFICIAL_REPOSITORY = "Neko-NF/Neko-Status-Mobile"
        const val PACKAGE_NAME = "com.nekonf.nekostatus"
        const val APK_SIZE = 31_457_280L
    }
}
