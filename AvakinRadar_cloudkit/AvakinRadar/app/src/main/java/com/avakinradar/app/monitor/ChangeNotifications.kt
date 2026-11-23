package com.avakinradar.app.monitor

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.avakinradar.app.data.MonitorSettings
import com.avakinradar.app.model.ChangeType
import com.avakinradar.app.model.OfferChange

object ChangeNotifications {
    private const val CHANNEL_ID = "store_changes"

    fun publish(context: Context, changes: List<OfferChange>, settings: MonitorSettings) {
        val allowed = changes.filter { change ->
            when (change.type) {
                ChangeType.NEW -> settings.notifyNew
                ChangeType.PRICE_CHANGED, ChangeType.CONTENT_CHANGED -> settings.notifyChanges
                ChangeType.REMOVED -> settings.notifyRemoved
            }
        }
        if (allowed.isEmpty()) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Изменения магазина", NotificationManager.IMPORTANCE_DEFAULT))
        }
        val lines = allowed.take(5).joinToString("\n") { "${label(it.type)}: ${it.offer.title}" }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Avakin Radar: ${allowed.size} измен.")
            .setContentText(allowed.first().offer.title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines))
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun label(type: ChangeType) = when (type) {
        ChangeType.NEW -> "Новое предложение"
        ChangeType.PRICE_CHANGED -> "Изменилась цена"
        ChangeType.CONTENT_CHANGED -> "Изменился состав"
        ChangeType.REMOVED -> "Предложение исчезло"
    }

    private const val NOTIFICATION_ID = 4021
}
