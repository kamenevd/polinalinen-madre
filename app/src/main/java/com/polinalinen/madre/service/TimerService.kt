package com.polinalinen.madre.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.polinalinen.madre.R
import com.polinalinen.madre.ui.MainActivity

class TimerService : Service() {

    companion object {
        private const val CHANNEL_ID = "levito_timer"
        private const val CHANNEL_NAME = "Таймер выпечки"
        private const val NOTIFICATION_ID_STEP = 1001
        private const val NOTIFICATION_ID_FOREGROUND = 1000

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
                .build()

            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID_STEP, notification)
        }

        fun startForeground(context: Context, recipeName: String, stepTitle: String) {
            createChannel(context)
            val intent = Intent(context, TimerService::class.java)
            context.startForegroundService(intent)
        }

        private fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Уведомления таймера выпечки Levito Madre"
                enableVibration(true)
                enableLights(true)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel(this)

        val notifyIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, notifyIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bread)
            .setContentTitle("🍞 Levito Madre")
            .setContentText("Таймер выпечки активен")
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIFICATION_ID_FOREGROUND,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(NOTIFICATION_ID_FOREGROUND, notification)
        }

        return START_STICKY
    }
}

class TimerNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Handle notification action (e.g., "Advance step")
        if (intent.action == "ACTION_ADVANCE") {
            // Send broadcast to ViewModel via MainActivity
            val broadcast = Intent("ACTION_ADVANCE_STEP")
            broadcast.setPackage(context.packageName)
            context.sendBroadcast(broadcast)
        }
    }
}
