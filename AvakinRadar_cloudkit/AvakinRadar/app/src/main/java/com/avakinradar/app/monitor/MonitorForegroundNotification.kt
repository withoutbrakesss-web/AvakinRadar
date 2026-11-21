package com.avakinradar.app.monitor

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ForegroundInfo
import android.content.pm.ServiceInfo
import com.avakinradar.app.R

object MonitorForegroundNotification {
    private const val CHANNEL_ID = "catalog_check_progress"
    private const val NOTIFICATION_ID = 4022

    fun create(context: Context): ForegroundInfo {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_ID,
                "Проверка каталога",
                NotificationManager.IMPORTANCE_LOW
            ))
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_radar)
            .setContentTitle("Avakin Radar проверяет магазин")
            .setContentText("Загружаю публичный каталог предложений")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(0, 0, true)
            .build()
        val serviceType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        } else 0
        return ForegroundInfo(NOTIFICATION_ID, notification, serviceType)
    }
}
