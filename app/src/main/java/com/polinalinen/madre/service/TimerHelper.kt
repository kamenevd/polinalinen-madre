package com.polinalinen.madre.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat

object TimerHelper {

    private const val CHANNEL_ID = "levito_timer"
    private const val CHANNEL_PROGRESS = "levito_progress"
    private const val NOTIFICATION_ID_COMPLETE = 1001
    private const val NOTIFICATION_ID_PROGRESS = 2000

    fun createChannel(context: Context) {
        try {
            // Complete notification channel
            val completeChannel = NotificationChannel(
                CHANNEL_ID,
                "Таймер выпечки",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления когда шаг завершён"
                enableVibration(true)
            }

            // Progress channel (silent, ongoing)
            val progressChannel = NotificationChannel(
                CHANNEL_PROGRESS,
                "Прогресс выпечки",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Текущий прогресс выпечки"
                enableVibration(false)
                setShowBadge(false)
            }

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(completeChannel)
            manager.createNotificationChannel(progressChannel)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun showStepCompleteNotification(context: Context, title: String) {
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
                .setContentText(title)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .build()

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID_COMPLETE, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun updateProgressNotification(
        context: Context,
        sessionId: String,
        sessionName: String,
        stepTitle: String,
        remainingSeconds: Long,
        totalSeconds: Long
    ) {
        try {
            createChannel(context)

            val hours = remainingSeconds / 3600
            val minutes = (remainingSeconds % 3600) / 60
            val seconds = remainingSeconds % 60
            val timeText = when {
                hours > 0 -> String.format("%d:%02d:%02d", hours, minutes, seconds)
                else -> String.format("%02d:%02d", minutes, seconds)
            }

            val progress = if (totalSeconds > 0) {
                ((totalSeconds - remainingSeconds) * 100 / totalSeconds).toInt()
            } else 0

            val notificationId = NOTIFICATION_ID_PROGRESS + (sessionId.hashCode() and 0xFFFF)

            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_PROGRESS)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("🍞 $sessionName")
                .setContentText("$stepTitle • $timeText осталось")
                .setProgress(100, progress, false)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pendingIntent)
                .build()

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(notificationId, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun cancelProgressNotification(context: Context, sessionId: String) {
        try {
            val notificationId = NOTIFICATION_ID_PROGRESS + (sessionId.hashCode() and 0xFFFF)
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(notificationId)
        } catch (_: Exception) {}
    }
}
