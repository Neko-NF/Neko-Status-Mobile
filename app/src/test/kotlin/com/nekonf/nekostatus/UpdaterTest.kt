package com.nekonf.nekostatus

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Environment
import com.nekonf.nekostatus.core.model.UpdateStatus
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
    @Test
    fun `verified update notification launches install activity and remains available`() {
        val context = RuntimeEnvironment.getApplication()

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
    fun `install activity finishes immediately`() {
        val activity = Robolectric.buildActivity(UpdateInstallActivity::class.java).create().get()

        assertTrue(activity.isFinishing)
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
