package com.avakinradar.app.monitor

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object MonitorScheduler {
    private const val PERIODIC_WORK = "avakin-radar-periodic"
    private const val MANUAL_WORK = "avakin-radar-manual"

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
        WorkManager.getInstance(context).cancelUniquePeriodicWork(PERIODIC_WORK)
        WorkManager.getInstance(context).cancelUniqueWork(MANUAL_WORK)
    }

    fun enqueueManual(context: Context) {
        val request = OneTimeWorkRequestBuilder<MonitorWorker>()
            .setInputData(Data.Builder().putBoolean(MonitorWorker.KEY_MANUAL, true).build())
            .setConstraints(networkConstraints())
            .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.MINUTES)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(MANUAL_WORK, ExistingWorkPolicy.KEEP, request)
    }

    private fun networkConstraints() = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()
}
