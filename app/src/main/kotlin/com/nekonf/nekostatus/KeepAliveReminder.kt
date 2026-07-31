package com.nekonf.nekostatus

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nekonf.nekostatus.core.data.SessionRepository
import com.nekonf.nekostatus.core.data.SettingsRepository
import com.nekonf.nekostatus.core.model.ReportingSettings
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

internal object KeepAliveReminderScheduler {
    const val EXTRA_RESUME_REPORTING = "com.nekonf.nekostatus.extra.RESUME_REPORTING"
    private const val PERIODIC_WORK = "neko-keep-alive-reminder"

    fun sync(
        context: Context,
        settings: ReportingSettings,
    ) {
        val workManager = WorkManager.getInstance(context)
        if (!settings.enabled || !settings.keepAliveReminderEnabled) {
            workManager.cancelUniqueWork(PERIODIC_WORK)
            KeepAliveReminderNotifier.dismiss(context)
            return
        }
        val intervalHours = normalizeReminderInterval(settings.keepAliveReminderIntervalHours)
        val request =
            PeriodicWorkRequestBuilder<KeepAliveReminderWorker>(intervalHours.toLong(), TimeUnit.HOURS)
                .setInitialDelay(intervalHours.toLong(), TimeUnit.HOURS)
                .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }
}

internal class KeepAliveReminderWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val entryPoint =
            EntryPointAccessors.fromApplication(applicationContext, KeepAliveReminderEntryPoint::class.java)
        val settings = entryPoint.settingsRepository().reportingSettings.first()
        if (!settings.enabled || !settings.keepAliveReminderEnabled) return Result.success()
        if (entryPoint.sessionRepository().deviceCredential.value == null) return Result.success()
        KeepAliveReminderNotifier.show(applicationContext)
        return Result.success()
    }
}

internal object KeepAliveReminderNotifier {
    private const val CHANNEL_ID = "neko-status-keep-alive"
    private const val NOTIFICATION_ID = 2002
    private const val CONTENT_REQUEST = 20
    private const val ACTION_REQUEST = 21

    fun show(context: Context) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.keep_alive_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = context.getString(R.string.keep_alive_channel_description) },
        )
        val openIntent =
            Intent(context, MainActivity::class.java)
                .putExtra(KeepAliveReminderScheduler.EXTRA_RESUME_REPORTING, true)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val contentIntent =
            PendingIntent.getActivity(
                context,
                CONTENT_REQUEST,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val keepAliveIntent =
            PendingIntent.getForegroundService(
                context,
                ACTION_REQUEST,
                Intent(context, ReportingService::class.java).setAction(ReportingService.ACTION_KEEP_ALIVE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_neko_monochrome)
                .setContentTitle(context.getString(R.string.keep_alive_notification_title))
                .setContentText(context.getString(R.string.keep_alive_notification_text))
                .setContentIntent(contentIntent)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .addAction(0, context.getString(R.string.keep_alive_notification_action), keepAliveIntent)
                .build()
        manager.notify(NOTIFICATION_ID, notification)
    }

    fun dismiss(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}

internal fun normalizeReminderInterval(hours: Int): Int = REMINDER_INTERVALS.minBy { kotlin.math.abs(it - hours) }

private val REMINDER_INTERVALS = listOf(6, 12, 24, 48)

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface KeepAliveReminderEntryPoint {
    fun settingsRepository(): SettingsRepository

    fun sessionRepository(): SessionRepository
}
