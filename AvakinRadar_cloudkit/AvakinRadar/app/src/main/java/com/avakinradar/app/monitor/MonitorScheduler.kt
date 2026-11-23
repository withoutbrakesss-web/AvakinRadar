package com.avakinradar.app.monitor

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OutOfQuotaPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object MonitorScheduler {
    const val PERIODIC_WORK = "avakin-radar-periodic"
    const val MANUAL_WORK = "avakin-radar-manual-v2"
    private const val LEGACY_MANUAL_WORK = "avakin-radar-manual"
    const val PROGRESS_PHASE = "progress_phase"

    fun enable(context: Context, intervalHours: Long) {
        enqueueManual(context)
        schedulePeriodic(context, intervalHours)
    }

    fun schedulePeriodic(context: Context, intervalHours: Long) {
        val request = PeriodicWorkRequestBuilder<MonitorWorker>(intervalHours.coerceIn(6L, 24L), TimeUnit.HOURS)
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_WORK, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    fun disable(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(PERIODIC_WORK)
        WorkManager.getInstance(context).cancelUniqueWork(MANUAL_WORK)
        WorkManager.getInstance(context).cancelUniqueWork(LEGACY_MANUAL_WORK)
    }

    fun cancelLegacyManual(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(LEGACY_MANUAL_WORK)
    }

    fun enqueueManual(context: Context, onRequestCreated: (String) -> Unit = {}): String {
        val request = OneTimeWorkRequestBuilder<MonitorWorker>()
            .setInputData(Data.Builder().putBoolean(MonitorWorker.KEY_MANUAL, true).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        val id = request.id.toString()
        onRequestCreated(id)
        WorkManager.getInstance(context).enqueueUniqueWork(MANUAL_WORK, ExistingWorkPolicy.REPLACE, request)
        return id
    }

    private fun networkConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
}
