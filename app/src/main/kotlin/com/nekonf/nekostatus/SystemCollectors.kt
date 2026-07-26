package com.nekonf.nekostatus

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.app.Notification
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.BatteryManager
import android.os.PowerManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.accessibility.AccessibilityEvent
import com.nekonf.nekostatus.core.data.InstallationRepository
import com.nekonf.nekostatus.core.model.DeviceSnapshot
import com.nekonf.nekostatus.core.model.MediaSnapshot
import com.nekonf.nekostatus.core.model.ScreenState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceSnapshotCollector
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val installationRepository: InstallationRepository,
    ) {
        fun capture(
            enhancedAppDetection: Boolean = false,
            includeMedia: Boolean = true,
        ): DeviceSnapshot {
            val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val packageName =
                NekoAccessibilityService.currentPackageName.takeIf { enhancedAppDetection }
                    ?: latestForegroundPackage()
                    ?: ""
            val appName =
                if (packageName.isBlank()) {
                    context.getString(R.string.unknown_app)
                } else {
                    runCatching {
                        val info = context.packageManager.getApplicationInfo(packageName, 0)
                        context.packageManager.getApplicationLabel(info).toString()
                    }.getOrDefault(packageName)
                }
            return DeviceSnapshot(
                installationId = installationRepository.installationId,
                appName = appName,
                packageName = packageName,
                batteryLevel = if (level >= 0 && scale > 0) (level * 100 / scale).coerceIn(0, 100) else 0,
                isCharging = charging,
                screenState = screenState(),
                media = NekoNotificationListenerService.currentMedia.takeIf { includeMedia },
                capturedAtEpochMs = System.currentTimeMillis(),
            )
        }

        fun loadAppIconPng(packageName: String): ByteArray? {
            if (packageName.isBlank()) return null
            return runCatching {
                val drawable = context.packageManager.getApplicationIcon(packageName)
                val bitmap = Bitmap.createBitmap(APP_ICON_SIZE, APP_ICON_SIZE, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(bitmap)
                drawable.setBounds(0, 0, canvas.width, canvas.height)
                drawable.draw(canvas)
                val bytes =
                    ByteArrayOutputStream().use { output ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                        output.toByteArray()
                    }
                bitmap.recycle()
                bytes
            }.getOrNull()
        }

        private fun screenState(): ScreenState {
            val power = context.getSystemService(PowerManager::class.java)
            val keyguard = context.getSystemService(KeyguardManager::class.java)
            return when {
                !power.isInteractive -> ScreenState.OFF
                keyguard.isKeyguardLocked -> ScreenState.LOCKED
                else -> ScreenState.ON
            }
        }

        private fun latestForegroundPackage(): String? {
            val manager = context.getSystemService(UsageStatsManager::class.java)
            val end = System.currentTimeMillis()
            val events = manager.queryEvents(end - 60_000, end)
            val event = UsageEvents.Event()
            var latestPackage: String? = null
            var latestTime = Long.MIN_VALUE
            while (events.hasNextEvent()) {
                events.getNextEvent(event)
                if ((event.eventType == UsageEvents.Event.ACTIVITY_RESUMED || event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) &&
                    event.timeStamp >= latestTime
                ) {
                    latestTime = event.timeStamp
                    latestPackage = event.packageName
                }
            }
            return latestPackage
        }
    }

private const val APP_ICON_SIZE = 96

class NekoAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            currentPackageName = event.packageName?.toString()?.takeIf { it != packageName }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        currentPackageName = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        var currentPackageName: String? = null
            private set
    }
}

class NekoNotificationListenerService : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val notification = sbn?.notification ?: return
        if (notification.category != Notification.CATEGORY_TRANSPORT &&
            !notification.extras.containsKey(Notification.EXTRA_MEDIA_SESSION)
        ) {
            return
        }
        val title =
            notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.takeIf(String::isNotBlank)
                ?: return
        currentKey = sbn.key
        currentMedia =
            MediaSnapshot(
                title = title,
                artist = notification.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
                album = notification.extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString(),
                packageName = sbn.packageName,
                isPlaying = true,
            )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        if (sbn?.key == currentKey) {
            currentKey = null
            currentMedia = null
        }
    }

    companion object {
        @Volatile
        private var currentKey: String? = null

        @Volatile
        var currentMedia: MediaSnapshot? = null
            private set
    }
}
