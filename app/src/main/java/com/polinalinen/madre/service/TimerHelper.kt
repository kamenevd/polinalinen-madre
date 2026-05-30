package com.polinalinen.madre.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import androidx.core.app.NotificationCompat
import com.polinalinen.madre.R

object TimerHelper {

    private const val CHANNEL_ID = "levito_timer"
    private const val CHANNEL_PROGRESS = "levito_progress"
    private const val CHANNEL_URGENT = "levito_urgent"
    private const val NOTIFICATION_ID_COMPLETE = 1001
    private const val NOTIFICATION_ID_PROGRESS = 2000
    private const val NOTIFICATION_ID_ACTION = 3000

    // Intent actions for notification buttons
    const val ACTION_ADVANCE_STEP = "com.polinalinen.madre.ADVANCE_STEP"
    const val ACTION_STEP_DONE = "com.polinalinen.madre.STEP_DONE"
    const val EXTRA_SESSION_ID = "SESSION_ID"

    fun createChannel(context: Context) {
        try {
            val completeChannel = NotificationChannel(
                CHANNEL_ID,
                "Таймер выпечки",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления когда шаг завершён"
                enableVibration(true)
                setSound(
                    android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION),
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build()
                )
            }

            val progressChannel = NotificationChannel(
                CHANNEL_PROGRESS,
                "Прогресс выпечки",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Текущий прогресс выпечки"
                enableVibration(false)
                setShowBadge(false)
            }

            val urgentChannel = NotificationChannel(
                CHANNEL_URGENT,
                "Срочные уведомления",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Меньше 5 минут осталось!"
                enableVibration(true)
                // Urgent uses same timer sound
                val soundUri = android.net.Uri.parse("android.resource://com.polinalinen.madre/${R.raw.notif_timer}")
                setSound(
                    soundUri,
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT).build()
                )
            }

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(completeChannel)
            manager.createNotificationChannel(progressChannel)
            manager.createNotificationChannel(urgentChannel)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * State 1: ACTION step notification
     * Title: "🍞 {recipe name}"
     * Text: "👨‍🍳 ДЕЛАЕМ: {step}"
     * Button: "Готово"
     */
    fun showActionStepNotification(
        context: Context,
        recipeName: String,
        stepTitle: String,
        sessionId: String = ""
    ) {
        try {
            createChannel(context)

            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                putExtra(EXTRA_SESSION_ID, sessionId)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context, sessionId.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("🍞 $recipeName")
                .setContentText("👨‍🍳 ДЕЛАЕМ: $stepTitle")
                .setStyle(NotificationCompat.BigTextStyle().bigText("👨‍🍳 ДЕЛАЕМ: $stepTitle"))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setSound(android.net.Uri.parse("android.resource://com.polinalinen.madre/${R.raw.notif_step}"))
                .setColor(0xFFC49A5C.toInt()) // AccentGold
                .build()

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID_ACTION + (sessionId.hashCode() and 0xFF), notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * State 2: WAIT step (normal) — progress notification
     * Title: "🍞 {recipe name}"
     * Text: "{step} • {timer}"
     * Progress bar
     */
    fun showWaitStepNotification(
        context: Context,
        sessionId: String,
        sessionName: String,
        stepTitle: String,
        remainingSeconds: Long,
        totalSeconds: Long
    ) {
        try {
            createChannel(context)

            val isUrgent = remainingSeconds in 1..299 // < 5 min and > 0

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

            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                putExtra(EXTRA_SESSION_ID, sessionId)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context, notificationId, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val channelId = if (isUrgent) CHANNEL_URGENT else CHANNEL_PROGRESS
            val contentText = if (isUrgent) {
                "⚠️ СРОЧНО $stepTitle • $timeText"
            } else {
                "$stepTitle • $timeText"
            }
            val color = if (isUrgent) 0xFFC4756E.toInt() else 0xFFC49A5C.toInt() // AccentRose vs AccentGold

            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("🍞 $sessionName")
                .setContentText(contentText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
                .setProgress(100, progress, false)
                .setOngoing(true)
                .setPriority(if (isUrgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pendingIntent)
                .setColor(color)
                .build()

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(notificationId, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * State 3: WAIT step (<5 min) — handled by showWaitStepNotification with isUrgent flag
     * Adds "⚠️ СРОЧНО" prefix and rose color
     */

    /**
     * State 4: Step completed notification
     * Title: "✅ {step} завершён!"
     * Text: "Далее: {next step}"
     * Button: "Начать шаг"
     */
    fun showStepCompleteNotification(
        context: Context,
        completedStepTitle: String,
        nextStepTitle: String?,
        recipeName: String = "",
        sessionId: String = ""
    ) {
        try {
            createChannel(context)

            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)?.apply {
                putExtra(EXTRA_SESSION_ID, sessionId)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context, sessionId.hashCode(), intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val contentText = if (nextStepTitle != null) {
                "Далее: $nextStepTitle"
            } else {
                "Рецепт «$recipeName» готов! 🎉"
            }

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("✅ $completedStepTitle завершён!")
                .setContentText(contentText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setColor(0xFF7FA870.toInt()) // StatusCompleted green
                .build()

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID_COMPLETE, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Backward-compatible overload for old call sites
     */
    fun showStepCompleteNotification(context: Context, title: String, sessionId: String = "") {
        showStepCompleteNotification(
            context = context,
            completedStepTitle = title,
            nextStepTitle = null,
            recipeName = "",
            sessionId = sessionId
        )
    }

    /**
     * Update progress notification — delegates to showWaitStepNotification
     */
    fun updateProgressNotification(
        context: Context,
        sessionId: String,
        sessionName: String,
        stepTitle: String,
        remainingSeconds: Long,
        totalSeconds: Long
    ) {
        showWaitStepNotification(
            context, sessionId, sessionName, stepTitle,
            remainingSeconds, totalSeconds
        )
    }

    fun cancelProgressNotification(context: Context, sessionId: String) {
        try {
            val notificationId = NOTIFICATION_ID_PROGRESS + (sessionId.hashCode() and 0xFFFF)
            val actionNotificationId = NOTIFICATION_ID_ACTION + (sessionId.hashCode() and 0xFF)
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.cancel(notificationId)
            manager.cancel(actionNotificationId)
        } catch (_: Exception) {}
    }
}
