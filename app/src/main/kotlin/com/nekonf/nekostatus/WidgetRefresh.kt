package com.nekonf.nekostatus

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nekonf.nekostatus.core.data.SessionRepository
import com.nekonf.nekostatus.core.data.SettingsRepository
import com.nekonf.nekostatus.core.data.WidgetRepository
import com.nekonf.nekostatus.core.data.WidgetSettingsRepository
import com.nekonf.nekostatus.core.model.OperationResult
import com.nekonf.nekostatus.core.model.WidgetFeed
import com.nekonf.nekostatus.core.model.WidgetSettings
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, WidgetWorkerEntryPoint::class.java)
        return try {
            refresh(entryPoint)
        } finally {
            WidgetRefreshFeedbackStore.finish(applicationContext)
            NekoWidgetRenderer.updateAll(applicationContext)
            NekoSnapshotWidgetRenderer.updateAll(applicationContext)
        }
    }

    private suspend fun refresh(entryPoint: WidgetWorkerEntryPoint): Result {
        val settings = entryPoint.widgetSettingsRepository().settings.value
        if (!settings.enabled) return Result.success()
        return when (val refresh = entryPoint.widgetRepository().refresh(settings)) {
            is OperationResult.Success -> {
                warmImages(entryPoint, refresh.value, settings)
                Result.success()
            }
            is OperationResult.Failure -> if (refresh.terminal) Result.success() else Result.retry()
        }
    }

    private suspend fun warmImages(
        entryPoint: WidgetWorkerEntryPoint,
        feed: WidgetFeed,
        settings: WidgetSettings,
    ) {
        val environment =
            WidgetImageEnvironment(
                context = applicationContext,
                serverUrl = entryPoint.settingsRepository().serverConfig.first().activeUrl,
                bearerToken = entryPoint.sessionRepository().widgetCredential.value?.token,
                client = entryPoint.okHttpClient(),
            )
        WidgetImageCache.warm(environment, feed, settings)
    }
}

object WidgetRefreshScheduler {
    private const val PERIODIC_WORK = "neko-widget-periodic-refresh"
    private const val IMMEDIATE_WORK = "neko-widget-immediate-refresh"

    fun sync(
        context: Context,
        enabled: Boolean,
        intervalMinutes: Int,
    ) {
        val workManager = WorkManager.getInstance(context)
        if (!enabled) {
            workManager.cancelUniqueWork(PERIODIC_WORK)
            updatePresentation(context)
            return
        }
        val request =
            PeriodicWorkRequestBuilder<WidgetRefreshWorker>(intervalMinutes.coerceIn(15, 180).toLong(), TimeUnit.MINUTES)
                .setConstraints(networkConstraints())
                .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
        updatePresentation(context)
    }

    fun refreshNow(context: Context) {
        WidgetRefreshFeedbackStore.start(context)
        updatePresentation(context)
        val request = OneTimeWorkRequestBuilder<WidgetRefreshWorker>().build()
        WorkManager.getInstance(context).enqueueUniqueWork(IMMEDIATE_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancelAll(context: Context) {
        val workManager = WorkManager.getInstance(context)
        workManager.cancelUniqueWork(PERIODIC_WORK)
        workManager.cancelUniqueWork(IMMEDIATE_WORK)
        WidgetRefreshFeedbackStore.finish(context)
        updatePresentation(context)
    }

    private fun updatePresentation(context: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            NekoWidgetRenderer.updateAll(context)
            NekoSnapshotWidgetRenderer.updateAll(context)
        }
    }

    private fun networkConstraints(): Constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
}

object WidgetRefreshFeedbackStore {
    private const val PREFERENCES = "neko-widget-refresh-feedback"
    private const val STARTED_AT = "started_at"
    private const val TIMEOUT_MS = 30_000L

    fun start(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putLong(STARTED_AT, System.currentTimeMillis())
            .apply()
    }

    fun finish(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().remove(STARTED_AT).apply()
    }

    fun isRefreshing(context: Context): Boolean {
        val startedAt = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getLong(STARTED_AT, 0L)
        return startedAt > 0L && System.currentTimeMillis() - startedAt < TIMEOUT_MS
    }
}

object WidgetImageCache {
    suspend fun warm(
        environment: WidgetImageEnvironment,
        feed: WidgetFeed,
        settings: WidgetSettings,
    ) = withContext(Dispatchers.IO) {
        buildRequests(feed, settings).forEach { cacheImage(environment, it) }
        trim(environment.context)
    }

    private fun buildRequests(
        feed: WidgetFeed,
        settings: WidgetSettings,
    ): List<WidgetImageRequest> =
        buildList {
            feed.users.take(MAX_USERS).forEach { user ->
                user.avatarUrl?.let { add(WidgetImageRequest(it, it)) }
                user.devices.take(MAX_DEVICES_PER_USER).forEach { device ->
                    device.appIconUrl?.let { add(WidgetImageRequest(it, it)) }
                }
            }
            addAll(screenshotRequests(feed, settings))
        }.distinctBy(WidgetImageRequest::cacheKey)

    private fun screenshotRequests(
        feed: WidgetFeed,
        settings: WidgetSettings,
    ): List<WidgetImageRequest> {
        if (!settings.showScreenshot) return emptyList()
        val user =
            feed.users.firstOrNull { it.userId == settings.targetUserId }
                ?: feed.users.firstOrNull()
        return screenshotDeviceSequence(user?.devices.orEmpty(), settings.targetDeviceId)
            .mapNotNull { device ->
                val source = device.screenshotThumbnailUrl ?: device.screenshotUrl
                source?.takeIf(String::isNotBlank)?.let {
                    WidgetImageRequest(
                        source = it,
                        cacheKey = "screenshot|${device.deviceId}|${device.screenshotUpdatedAt.orEmpty()}|$it",
                    )
                }
            }
    }

    private fun cacheImage(
        environment: WidgetImageEnvironment,
        imageRequest: WidgetImageRequest,
    ) {
        val target = cacheFile(environment.context, imageRequest.cacheKey)
        if (target.isFile && target.length() > 0L) return
        val resolved = resolveUrl(environment.serverUrl, imageRequest.source) ?: return
        download(environment, resolved)?.let(target::writeBytes)
    }

    private fun download(
        environment: WidgetImageEnvironment,
        resolvedUrl: String,
    ): ByteArray? =
        runCatching {
            val request =
                Request.Builder().url(resolvedUrl).apply {
                    environment.bearerToken?.let { header("Authorization", "Bearer $it") }
                }.build()
            environment.client.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.bytes()?.takeIf(ByteArray::isNotEmpty) else null
            }
        }.getOrNull()

    fun readScaled(
        context: Context,
        source: String?,
        targetSizePx: Int,
    ): Bitmap? {
        val file = source?.let { cacheFile(context, it).takeIf(File::isFile) } ?: return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        var sampleSize = 1
        while (options.outWidth / sampleSize > targetSizePx * 2 || options.outHeight / sampleSize > targetSizePx * 2) {
            sampleSize *= 2
        }
        val decoded =
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sampleSize })
                ?: return null
        return if (decoded.width == targetSizePx && decoded.height == targetSizePx) {
            decoded
        } else {
            Bitmap.createScaledBitmap(decoded, targetSizePx, targetSizePx, true).also {
                if (it !== decoded) decoded.recycle()
            }
        }
    }

    fun readFitted(
        context: Context,
        source: String?,
        cacheKey: String,
        maxWidthPx: Int,
        maxHeightPx: Int,
    ): Bitmap? =
        source
            ?.takeIf(String::isNotBlank)
            ?.let { cacheFile(context, cacheKey).takeIf(File::isFile) }
            ?.let { decodeFitted(it, maxWidthPx, maxHeightPx) }

    private fun decodeFitted(
        file: File,
        maxWidthPx: Int,
        maxHeightPx: Int,
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sampleSize = 1
        while (bounds.outWidth / sampleSize > maxWidthPx * 2 || bounds.outHeight / sampleSize > maxHeightPx * 2) {
            sampleSize *= 2
        }
        val decoded =
            BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sampleSize })
                ?: return null
        return decoded.scaleToFit(maxWidthPx, maxHeightPx)
    }

    private fun Bitmap.scaleToFit(
        maxWidthPx: Int,
        maxHeightPx: Int,
    ): Bitmap {
        val decoded = this
        val scale = minOf(maxWidthPx.toFloat() / decoded.width, maxHeightPx.toFloat() / decoded.height, 1f)
        if (scale >= 1f) return decoded
        val targetWidth = (decoded.width * scale).toInt().coerceAtLeast(1)
        val targetHeight = (decoded.height * scale).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true).also {
            if (it !== decoded) decoded.recycle()
        }
    }

    private fun resolveUrl(
        serverUrl: String,
        source: String,
    ): String? =
        runCatching {
            if (source.startsWith("http://") || source.startsWith("https://")) {
                source.toHttpUrl().toString()
            } else {
                "${serverUrl.trimEnd('/')}/".toHttpUrl().resolve(source)?.toString()
            }
        }.getOrNull()

    private fun cacheFile(
        context: Context,
        source: String,
    ): File {
        val directory = File(context.cacheDir, "widget-images").apply { mkdirs() }
        val name =
            MessageDigest.getInstance("SHA-256").digest(source.toByteArray()).joinToString("") {
                "%02x".format(it.toInt() and 0xff)
            }
        return File(directory, name)
    }

    private fun trim(context: Context) {
        val files = File(context.cacheDir, "widget-images").listFiles()?.sortedByDescending(File::lastModified).orEmpty()
        files.drop(MAX_CACHE_FILES).forEach(File::delete)
    }

    fun clear(context: Context) {
        File(context.cacheDir, "widget-images").listFiles().orEmpty().forEach(File::delete)
    }

    private const val MAX_USERS = 4
    private const val MAX_DEVICES_PER_USER = 8
    private const val MAX_CACHE_FILES = 24

    private data class WidgetImageRequest(
        val source: String,
        val cacheKey: String,
    )
}

data class WidgetImageEnvironment(
    val context: Context,
    val serverUrl: String,
    val bearerToken: String?,
    val client: OkHttpClient,
)

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetWorkerEntryPoint {
    fun widgetRepository(): WidgetRepository

    fun widgetSettingsRepository(): WidgetSettingsRepository

    fun settingsRepository(): SettingsRepository

    fun sessionRepository(): SessionRepository

    fun okHttpClient(): OkHttpClient
}
