package com.polinalinen.madre.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.polinalinen.madre.R

object TimerHelper {

    private const val CHANNEL_ID = "levito_timer_v4"
    private const val CHANNEL_PROGRESS = "levito_progress_v4"
    private const val CHANNEL_URGENT = "levito_urgent_v4"

    // Legacy channel IDs — delete on first launch so stale config doesn't persist
    private const val CHANNEL_ID_LEGACY = "levito_timer"
    private const val CHANNEL_ID_LEGACY_V2 = "levito_timer_v2"
    private const val CHANNEL_PROGRESS_LEGACY = "levito_progress_v2"
    private const val CHANNEL_PROGRESS_LEGACY_V1 = "levito_progress"
    private const val CHANNEL_PROGRESS_LEGACY_V3 = "levito_progress_v3"
    private const val CHANNEL_URGENT_LEGACY = "levito_urgent"
    private const val CHANNEL_URGENT_LEGACY_V2 = "levito_urgent_v2"
    private const val NOTIFICATION_ID_COMPLETE = 1001
    private const val NOTIFICATION_ID_PROGRESS = 2000
    private const val NOTIFICATION_ID_ACTION = 3000

    // Intent actions for notification buttons
    const val ACTION_ADVANCE_STEP = "com.polinalinen.madre.ADVANCE_STEP"
    const val ACTION_STEP_DONE = "com.polinalinen.madre.STEP_DONE"
    const val EXTRA_SESSION_ID = "SESSION_ID"

    fun createChannel(context: Context) {
        try {
            // System default notification sound — works on ALL devices including Huawei
            val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

            // Delete legacy channels so stale (silent) config doesn't persist
            manager.deleteNotificationChannel(CHANNEL_ID_LEGACY)
            manager.deleteNotificationChannel(CHANNEL_ID_LEGACY_V2)
            manager.deleteNotificationChannel(CHANNEL_PROGRESS_LEGACY)
            manager.deleteNotificationChannel(CHANNEL_PROGRESS_LEGACY_V1)
            manager.deleteNotificationChannel(CHANNEL_PROGRESS_LEGACY_V3)
            manager.deleteNotificationChannel(CHANNEL_URGENT_LEGACY)
            manager.deleteNotificationChannel(CHANNEL_URGENT_LEGACY_V2)

            val completeChannel = NotificationChannel(
                CHANNEL_ID,
                "Таймер выпечки",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления когда шаг завершён"
                enableVibration(true)
                setSound(
                    defaultSoundUri,
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
                setSound(null, null) // Тихое обновление — без звука
                setShowBadge(false)
            }

            val urgentChannel = NotificationChannel(
                CHANNEL_URGENT,
                "Срочные уведомления",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Меньше 5 минут осталось!"
                enableVibration(true)
                setSound(
                    defaultSoundUri,
                    AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build()
                )
            }

            manager.createNotificationChannel(completeChannel)
            manager.createNotificationChannel(progressChannel)
            manager.createNotificationChannel(urgentChannel)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Build custom RemoteViews for wait step notification (Concept 3)
     */
    private fun buildWaitRemoteViews(
        context: Context,
        recipeName: String,
        stepTitle: String,
        timeText: String,
        progress: Int,
        isUrgent: Boolean,
        currentStepIndex: Int,
        totalSteps: Int,
        nextStepTitle: String?,
        nextStepTime: String?
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.notification_timer)

        // System colors — readable on all Android versions and OEM skins
        val primaryColor = context.getColor(android.R.color.primary_text_dark)
        val secondaryColor = context.getColor(android.R.color.secondary_text_dark)

        // Primary text (titles, timer, recipe name)
        views.setTextColor(R.id.notif_app_name, primaryColor)
        views.setTextColor(R.id.notif_recipe, primaryColor)
        views.setTextColor(R.id.notif_timer, primaryColor)

        // Secondary text (subtitles, hints, time label)
        views.setTextColor(R.id.notif_time, secondaryColor)
        views.setTextColor(R.id.notif_step_name, secondaryColor)
        views.setTextColor(R.id.notif_dots_text, secondaryColor)
        views.setTextColor(R.id.notif_next_name, secondaryColor)
        views.setTextColor(R.id.notif_next_time, secondaryColor)

        // Recipe name
        views.setTextViewText(R.id.notif_recipe, recipeName)

        // Step badge + name
        views.setTextViewText(R.id.notif_step_badge, if (isUrgent) "⚠️ СРОЧНО" else "⏳ ЖДЁМ")
        views.setTextViewText(R.id.notif_step_name, stepTitle)

        // Timer
        views.setTextViewText(R.id.notif_timer, timeText)

        // Progress bar — system drawable (gold fill, semi-transparent white bg)
        views.setProgressBar(R.id.notif_progress, 100, progress, false)

        // Step dots (text-based for RemoteViews compatibility)
        val dotsText = buildString {
            for (i in 0 until totalSteps) {
                when {
                    i < currentStepIndex -> append("● ")
                    i == currentStepIndex -> append("▸ ")
                    else -> append("○ ")
                }
            }
        }.trim()
        views.setTextViewText(R.id.notif_dots_text, dotsText)

        // Next step
        if (nextStepTitle != null) {
            views.setTextViewText(R.id.notif_next_name, nextStepTitle)
            views.setTextViewText(R.id.notif_next_time, nextStepTime ?: "")
            views.setViewVisibility(R.id.notif_next_container, android.view.View.VISIBLE)
        } else {
            views.setViewVisibility(R.id.notif_next_container, android.view.View.GONE)
        }

        return views
    }

    /**
     * State 1: ACTION step notification
     * Uses standard NotificationCompat (no timer to show)
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
                .setSmallIcon(R.drawable.ic_bread)
                .setContentTitle("🍞 $recipeName")
                .setContentText("👨‍🍳 ДЕЛАЕМ: $stepTitle")
                .setStyle(NotificationCompat.BigTextStyle().bigText("👨‍🍳 ДЕЛАЕМ: $stepTitle"))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setColor(0xFFC49A5C.toInt())
                .build()

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID_ACTION + (sessionId.hashCode() and 0xFF), notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * State 2 & 3: WAIT step (normal or urgent) — custom layout with timer, progress bar, step info
     */
    fun showWaitStepNotification(
        context: Context,
        sessionId: String,
        sessionName: String,
        stepTitle: String,
        remainingSeconds: Long,
        totalSeconds: Long,
        currentStepIndex: Int = 0,
        totalSteps: Int = 1,
        nextStepTitle: String? = null,
        nextStepTime: String? = null
    ) {
        try {
            createChannel(context)

            val isUrgent = remainingSeconds in 1..299

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
            val color = if (isUrgent) 0xFFC4756E.toInt() else 0xFFC49A5C.toInt()

            // Build custom RemoteViews (Concept 3 layout)
            val customView = buildWaitRemoteViews(
                context = context,
                recipeName = sessionName,
                stepTitle = stepTitle,
                timeText = timeText,
                progress = progress,
                isUrgent = isUrgent,
                currentStepIndex = currentStepIndex,
                totalSteps = totalSteps,
                nextStepTitle = nextStepTitle,
                nextStepTime = nextStepTime
            )

            // Fallback text for wearable/lock screen
            val fallbackText = if (isUrgent) {
                "⚠️ СРОЧНО $stepTitle • $timeText"
            } else {
                "$stepTitle • $timeText"
            }

            val notification = NotificationCompat.Builder(context, channelId)
                .setSmallIcon(R.drawable.ic_bread)
                .setContentTitle("🍞 $sessionName")
                .setContentText(fallbackText)
                .setCustomContentView(customView)
                .setCustomBigContentView(customView)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setOngoing(true)
                .setPriority(if (isUrgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_LOW)
                .setContentIntent(pendingIntent)
                .setColor(color)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .build()

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(notificationId, notification)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * State 4: Step completed notification
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
                .setSmallIcon(R.drawable.ic_bread)
                .setContentTitle("✅ $completedStepTitle завершён!")
                .setContentText(contentText)
                .setStyle(NotificationCompat.BigTextStyle().bigText(contentText))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setColor(0xFF7FA870.toInt())
                .build()

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID_COMPLETE + (sessionId.hashCode() and 0xFF), notification)
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
     * Update progress notification — delegates to showWaitStepNotification with full step info
     */
    fun updateProgressNotification(
        context: Context,
        sessionId: String,
        sessionName: String,
        stepTitle: String,
        remainingSeconds: Long,
        totalSeconds: Long,
        currentStepIndex: Int = 0,
        totalSteps: Int = 1,
        nextStepTitle: String? = null,
        nextStepTime: String? = null
    ) {
        showWaitStepNotification(
            context, sessionId, sessionName, stepTitle,
            remainingSeconds, totalSeconds,
            currentStepIndex, totalSteps,
            nextStepTitle, nextStepTime
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
