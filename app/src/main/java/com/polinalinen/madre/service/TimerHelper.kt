package com.polinalinen.madre.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

object TimerHelper {

    private const val CHANNEL_ID = "levito_timer"
    private const val NOTIFICATION_ID = 1001

    fun createChannel(context: Context) {
        try {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Таймер выпечки",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления таймера Levito Madre"
                enableVibration(true)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun showStepCompleteNotification(context: Context, stepTitle: String) {
        try {
            createChannel(context)

            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("🍞 Пора действовать!")
                .setContentText("Шаг завершён: $stepTitle")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .build()

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
