package com.nekonf.nekostatus

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nekonf.nekostatus.core.data.DiagnosticsRepository
import com.nekonf.nekostatus.core.data.ReportingStateStore
import com.nekonf.nekostatus.core.data.SettingsRepository
import com.nekonf.nekostatus.core.data.StatusRepository
import com.nekonf.nekostatus.core.model.ReportOutcome
import com.nekonf.nekostatus.core.model.RetryPolicy
import com.nekonf.nekostatus.core.model.ScreenState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.random.Random

@AndroidEntryPoint
class ReportingService : Service() {
    @Inject lateinit var statusRepository: StatusRepository

    @Inject lateinit var reportingStateStore: ReportingStateStore

    @Inject lateinit var settingsRepository: SettingsRepository

    @Inject lateinit var diagnosticsRepository: DiagnosticsRepository

    @Inject lateinit var collector: DeviceSnapshotCollector

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val triggers = Channel<Unit>(Channel.CONFLATED)
    private var loopJob: Job? = null
    private var registered = false
    private var failures = 0
    private var retryNotBefore = 0L

    @Volatile
    private var stopping = false

    private val eventReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(
                context: Context?,
                intent: Intent?,
            ) {
                triggers.trySend(Unit)
            }
        }

    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                triggers.trySend(Unit)
            }
        }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, notification())
        registerSignals()
        reportingStateStore.update { it.copy(isRunning = true, lastErrorCode = null, lastErrorMessage = null) }
        scope.launch { diagnosticsRepository.log("INFO", "service", "Reporting service started") }
        loopJob = scope.launch { reportingLoop() }
        triggers.trySend(Unit)
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopReporting(explicit = true, reportOffline = true)
                return START_NOT_STICKY
            }
            else ->
                scope.launch {
                    val settings = settingsRepository.reportingSettings.first()
                    settingsRepository.updateReporting(settings.copy(enabled = true))
                    triggers.trySend(Unit)
                }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        if (registered) {
            unregisterReceiver(eventReceiver)
            runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        }
        loopJob?.cancel()
        scope.cancel()
        reportingStateStore.update { it.copy(isRunning = false) }
        super.onDestroy()
    }

    private suspend fun reportingLoop() {
        while (scope.isActive) {
            val settings = settingsRepository.reportingSettings.first()
            val intervalMs = settings.intervalSeconds.coerceIn(10, 300) * 1_000L
            withTimeoutOrNull(intervalMs) { triggers.receive() }
            val snapshot = collector.capture(settings.enhancedAppDetection, settings.includeMedia)
            reportingStateStore.update {
                it.copy(currentSnapshot = snapshot, lastAttemptEpochMs = System.currentTimeMillis())
            }
            if (!networkAvailable() || System.currentTimeMillis() < retryNotBefore) continue

            val icon = collector.loadAppIconPng(snapshot.packageName)
            when (val outcome = statusRepository.report(snapshot, BuildConfig.VERSION_NAME, icon)) {
                is ReportOutcome.Success -> {
                    failures = 0
                    retryNotBefore = 0L
                    reportingStateStore.update {
                        it.copy(
                            lastSuccessEpochMs = System.currentTimeMillis(),
                            lastErrorCode = null,
                            lastErrorMessage = null,
                            consecutiveFailures = 0,
                        )
                    }
                }
                is ReportOutcome.Retryable -> {
                    failures++
                    val waitSeconds =
                        RetryPolicy.delaySeconds(
                            consecutiveFailures = failures,
                            retryAfterSeconds = outcome.retryAfterSeconds,
                            jitterFraction = Random.nextDouble(0.0, 0.25),
                        )
                    retryNotBefore = System.currentTimeMillis() + waitSeconds * 1_000
                    reportingStateStore.update {
                        it.copy(
                            lastErrorCode = outcome.code,
                            lastErrorMessage = outcome.message,
                            consecutiveFailures = failures,
                        )
                    }
                    diagnosticsRepository.log("WARN", "report", "${outcome.code}: ${outcome.message}")
                }
                is ReportOutcome.Terminal -> {
                    reportingStateStore.update {
                        it.copy(lastErrorCode = outcome.code, lastErrorMessage = outcome.message)
                    }
                    diagnosticsRepository.log("ERROR", "report", "${outcome.code}: ${outcome.message}")
                    stopReporting(explicit = true, reportOffline = false)
                    return
                }
            }
        }
    }

    private fun stopReporting(
        explicit: Boolean,
        reportOffline: Boolean,
    ) {
        if (stopping) return
        stopping = true
        scope.launch {
            if (reportOffline) {
                loopJob?.cancelAndJoin()
                val settings = settingsRepository.reportingSettings.first()
                val snapshot =
                    collector.capture(settings.enhancedAppDetection, settings.includeMedia).copy(screenState = ScreenState.OFF)
                reportingStateStore.update {
                    it.copy(currentSnapshot = snapshot, lastAttemptEpochMs = System.currentTimeMillis())
                }
                when (
                    val outcome =
                        statusRepository.report(
                            snapshot,
                            BuildConfig.VERSION_NAME,
                            collector.loadAppIconPng(snapshot.packageName),
                        )
                ) {
                    is ReportOutcome.Success ->
                        reportingStateStore.update {
                            it.copy(
                                lastSuccessEpochMs = System.currentTimeMillis(),
                                lastErrorCode = null,
                                lastErrorMessage = null,
                            )
                        }
                    is ReportOutcome.Retryable ->
                        diagnosticsRepository.log("WARN", "report", "Final offline report deferred: ${outcome.code}")
                    is ReportOutcome.Terminal ->
                        diagnosticsRepository.log("ERROR", "report", "Final offline report rejected: ${outcome.code}")
                }
            }
            if (explicit) {
                val settings = settingsRepository.reportingSettings.first()
                settingsRepository.updateReporting(settings.copy(enabled = false))
            }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun registerSignals() {
        val filter =
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_BATTERY_CHANGED)
            }
        ContextCompat.registerReceiver(this, eventReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        runCatching { getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback) }
        registered = true
    }

    private fun networkAvailable(): Boolean {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        return connectivity.activeNetwork != null
    }

    private fun notification(): android.app.Notification {
        val contentIntent =
            PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val stopIntent =
            PendingIntent.getService(
                this,
                1,
                Intent(this, ReportingService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_neko_monochrome)
            .setContentTitle(getString(R.string.reporting_notification_title))
            .setContentText(getString(R.string.reporting_notification_text))
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addAction(0, getString(R.string.reporting_stop), stopIntent)
            .build()
    }

    private fun createNotificationChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.reporting_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = getString(R.string.reporting_channel_description) }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "neko-status-reporting"
        private const val NOTIFICATION_ID = 2001
        private const val ACTION_START = "com.nekonf.nekostatus.action.START"
        private const val ACTION_STOP = "com.nekonf.nekostatus.action.STOP"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, ReportingService::class.java).setAction(ACTION_START))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ReportingService::class.java).setAction(ACTION_STOP))
        }

        fun stopImmediately(context: Context) {
            context.stopService(Intent(context, ReportingService::class.java))
        }
    }
}
