package com.nekonf.nekostatus

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.nekonf.nekostatus.core.data.DiagnosticsRepository
import com.nekonf.nekostatus.core.data.SettingsRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first

class RecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(
        context: Context,
        intent: Intent?,
    ) {
        if (intent?.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val request =
            OneTimeWorkRequestBuilder<RecoveryWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
        WorkManager.getInstance(context).enqueueUniqueWork(RECOVERY_WORK, ExistingWorkPolicy.REPLACE, request)
    }

    companion object {
        private const val RECOVERY_WORK = "neko-reporting-recovery"
    }
}

class RecoveryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val entryPoint = EntryPointAccessors.fromApplication(applicationContext, RecoveryEntryPoint::class.java)
        val settings = entryPoint.settingsRepository().reportingSettings.first()
        if (!settings.enabled || !settings.restoreAfterBoot) return Result.success()
        return runCatching {
            ReportingService.start(applicationContext)
            entryPoint.diagnosticsRepository().log("INFO", "recovery", "Requested compliant service recovery")
            Result.success()
        }.getOrElse {
            entryPoint.diagnosticsRepository().log("WARN", "recovery", "Service recovery deferred: ${it.message}")
            Result.retry()
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
interface RecoveryEntryPoint {
    fun settingsRepository(): SettingsRepository

    fun diagnosticsRepository(): DiagnosticsRepository
}
