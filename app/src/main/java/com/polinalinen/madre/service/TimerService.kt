package com.polinalinen.madre.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.polinalinen.madre.R
import com.polinalinen.madre.ui.MainActivity

/**
 * Timer notification states matching concept3-notifications.svg:
 * - ACTIVE  (ДЕЛАЕМ)  — gold accent, step in progress
 * - WAITING (ЖДЁМ)    — teal accent, timer counting down
 * - URGENT  (СРОЧНО)  — red/coral accent, < 5 min remaining
 * - DONE    (ЗАВЕРШЁН) — green accent, step complete
 */
enum class NotifState {
    ACTIVE,
    WAITING,
    URGENT,
    DONE
}

class TimerService : Service() {

    companion object {
        private const val CHANNEL_ID = "levito_timer"
        private const val CHANNEL_NAME = "Таймер выпечки"
        const val NOTIFICATION_ID_FOREGROUND = 1000
        const val NOTIFICATION_ID_STEP = 1001

        // Broadcast actions
        const val ACTION_PAUSE = "com.polinalinen.madre.ACTION_PAUSE"
        const val ACTION_RESUME = "com.polinalinen.madre.ACTION_RESUME"
        const val ACTION_OPEN = "com.polinalinen.madre.ACTION_OPEN"
        const val ACTION_DISMISS = "com.polinalinen.madre.ACTION_DISMISS"

        // Intent extras
        const val EXTRA_RECIPE = "recipe_name"
        const val EXTRA_STEP_NAME = "step_name"
        const val EXTRA_TIME_REMAINING = "time_remaining"
        const val EXTRA_TIME_TOTAL = "time_total"
        const val EXTRA_STEP_INDEX = "step_index"
        const val EXTRA_STEP_COUNT = "step_count"
        const val EXTRA_NEXT_NAME = "next_step_name"
        const val EXTRA_NEXT_TIME = "next_step_time"
        const val EXTRA_STATE = "notif_state"

        /**
         * Build and show a custom notification with RemoteViews
         */
        fun showNotification(
            context: Context,
            state: NotifState,
            recipeName: String,
            stepName: String,
            timeRemaining: Long,
            timeTotal: Long,
            stepIndex: Int,
            stepCount: Int,
            nextStepName: String? = null,
            nextStepTime: String? = null
        ) {
            createChannel(context)

            val contentView = RemoteViews(context.packageName, R.layout.notification_timer)

            // Recipe name
            contentView.setTextViewText(R.id.notif_recipe, recipeName)

            // Step badge color & text based on state
            when (state) {
                NotifState.ACTIVE -> {
                    contentView.setTextViewText(R.id.notif_step_badge, "🍞 ДЕЛАЕМ")
                    contentView.setTextColor(R.id.notif_step_badge, 0xFFE8C9A0.toInt())
                    contentView.setInt(R.id.notif_step_badge, "setBackgroundColor", 0x1AE8C9A0.toInt())
                    contentView.setTextColor(R.id.notif_timer, 0xFFF5EDE4.toInt())
                    contentView.setInt(R.id.notif_progress, "setProgressDrawable", R.drawable.notif_progress_gold)
                }
                NotifState.WAITING -> {
                    contentView.setTextViewText(R.id.notif_step_badge, "⏳ ЖДЁМ")
                    contentView.setTextColor(R.id.notif_step_badge, 0xFF6B9DA3.toInt())
                    contentView.setInt(R.id.notif_step_badge, "setBackgroundColor", 0x1A6B9DA3.toInt())
                    contentView.setTextColor(R.id.notif_timer, 0xFFF5EDE4.toInt())
                    contentView.setInt(R.id.notif_progress, "setProgressDrawable", R.drawable.notif_progress_gold)
                }
                NotifState.URGENT -> {
                    contentView.setTextViewText(R.id.notif_step_badge, "🔥 СРОЧНО")
                    contentView.setTextColor(R.id.notif_step_badge, 0xFFC4756E.toInt())
                    contentView.setInt(R.id.notif_step_badge, "setBackgroundColor", 0x1AC4756E.toInt())
                    contentView.setTextColor(R.id.notif_timer, 0xFFC4756E.toInt())
                    contentView.setInt(R.id.notif_progress, "setProgressDrawable", R.drawable.notif_progress_urgent)
                }
                NotifState.DONE -> {
                    contentView.setTextViewText(R.id.notif_step_badge, "✅ ЗАВЕРШЁН")
                    contentView.setTextColor(R.id.notif_step_badge, 0xFF7FA870.toInt())
                    contentView.setInt(R.id.notif_step_badge, "setBackgroundColor", 0x1A7FA870.toInt())
                    contentView.setTextColor(R.id.notif_timer, 0xFF7FA870.toInt())
                    contentView.setInt(R.id.notif_progress, "setProgressDrawable", R.drawable.notif_progress_done)
                }
            }

            // Step name
            contentView.setTextViewText(R.id.notif_step_name, stepName)

            // Timer display
            val hours = timeRemaining / 3600
            val minutes = (timeRemaining % 3600) / 60
            val seconds = timeRemaining % 60
            val timeText = if (hours > 0) {
                String.format("%d:%02d:%02d", hours, minutes, seconds)
            } else {
                String.format("%02d:%02d", minutes, seconds)
            }
            contentView.setTextViewText(R.id.notif_timer, timeText)

            // Progress
            val progress = if (timeTotal > 0) {
                ((timeTotal - timeRemaining) * 100 / timeTotal).toInt().coerceIn(0, 100)
            } else 0
            contentView.setProgressBar(R.id.notif_progress, 100, progress, false)

            // Step dots as text
            val dots = buildString {
                for (i in 0 until stepCount) {
                    when {
                        i < stepIndex -> append("● ")       // completed
                        i == stepIndex -> append("▶ ")      // current
                        else -> append("○ ")                 // upcoming
                    }
                }
            }
            contentView.setTextViewText(R.id.notif_dots_text, dots.trim())

            // Next step info
            if (!nextStepName.isNullOrEmpty()) {
                contentView.setTextViewText(R.id.notif_next_name, nextStepName)
                contentView.setTextViewText(R.id.notif_next_time, nextStepTime ?: "")
            } else {
                contentView.setTextViewText(R.id.notif_next_name, "— это последний шаг!")
                contentView.setTextViewText(R.id.notif_next_time, "")
            }

            // Build notification
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val openPending = PendingIntent.getActivity(
                context, 0, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // Pause action
            val pauseIntent = Intent(context, TimerNotificationReceiver::class.java).apply {
                action = ACTION_PAUSE
            }
            val pausePending = PendingIntent.getBroadcast(
                context, 1, pauseIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_bread)
                .setCustomContentView(contentView)
                .setCustomBigContentView(contentView)
                .setStyle(NotificationCompat.DecoratedCustomViewStyle())
                .setContentIntent(openPending)
                .setOngoing(state != NotifState.DONE)
                .setAutoCancel(state == NotifState.DONE)
                .setDefaults(0)
                .setSound(null) // We handle sound manually

            // Priority based on state
            when (state) {
                NotifState.URGENT -> {
                    builder.setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setVibrate(longArrayOf(0, 300, 200, 300))
                }
                NotifState.DONE -> {
                    builder.setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setVibrate(longArrayOf(0, 200))
                }
                else -> {
                    builder.setPriority(NotificationCompat.PRIORITY_LOW)
                }
            }

            // Add action buttons for non-done states
            if (state != NotifState.DONE) {
                builder.addAction(
                    R.drawable.ic_bread,
                    "⏸ Пауза",
                    pausePending
                )
            }

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID_FOREGROUND, builder.build())
        }

        /**
         * Show step-complete notification with sound
         */
        fun showStepCompleteNotification(context: Context, stepTitle: String) {
            createChannel(context)

            val intent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val pendingIntent = PendingIntent.getActivity(
                context, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_bread)
                .setContentTitle("🍞 Пора действовать!")
                .setContentText("Шаг завершён: $stepTitle")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setVibrate(longArrayOf(0, 300, 200, 300))
                .setSound(android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION))
                .build()

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID_STEP, notification)
        }

        fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления таймера выпечки Levito Madre"
                enableVibration(true)
                enableLights(true)
                lightColor = 0xC49A5C.toInt()
                setSound(
                    android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION),
                    android.app.Notification.AUDIO_ATTRIBUTES_DEFAULT
                )
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel(this)

        val recipeName = intent?.getStringExtra(EXTRA_RECIPE) ?: "Выпечка"
        val stepName = intent?.getStringExtra(EXTRA_STEP_NAME) ?: "Таймер активен"
        val timeRemaining = intent?.getLongExtra(EXTRA_TIME_REMAINING, 0L) ?: 0L
        val timeTotal = intent?.getLongExtra(EXTRA_TIME_TOTAL, 0L) ?: 0L
        val stepIndex = intent?.getIntExtra(EXTRA_STEP_INDEX, 0) ?: 0
        val stepCount = intent?.getIntExtra(EXTRA_STEP_COUNT, 1) ?: 1
        val nextName = intent?.getStringExtra(EXTRA_NEXT_NAME)
        val nextTime = intent?.getStringExtra(EXTRA_NEXT_TIME)
        val stateName = intent?.getStringExtra(EXTRA_STATE) ?: NotifState.ACTIVE.name
        val state = try { NotifState.valueOf(stateName) } catch (_: Exception) { NotifState.ACTIVE }

        showNotification(
            this, state, recipeName, stepName,
            timeRemaining, timeTotal,
            stepIndex, stepCount,
            nextName, nextTime
        )

        // Start as foreground with minimal notification
        val notifyIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notifyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val fgNotification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bread)
            .setContentTitle("🍞 $recipeName")
            .setContentText(stepName)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID_FOREGROUND,
                fgNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID_FOREGROUND, fgNotification)
        }

        return START_STICKY
    }
}

class TimerNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            TimerService.ACTION_PAUSE, TimerService.ACTION_RESUME -> {
                val broadcast = Intent("ACTION_TOGGLE_PAUSE")
                broadcast.setPackage(context.packageName)
                context.sendBroadcast(broadcast)
            }
            TimerService.ACTION_DISMISS -> {
                val serviceIntent = Intent(context, TimerService::class.java)
                context.stopService(serviceIntent)
            }
        }
    }
}
