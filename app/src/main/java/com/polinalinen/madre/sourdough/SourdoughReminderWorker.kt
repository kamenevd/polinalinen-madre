package com.polinalinen.madre.sourdough

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.*
import java.util.concurrent.TimeUnit

class SourdoughReminderWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val action = inputData.getString("action") ?: "check"
        val context = applicationContext

        when (action) {
            "reminder_soft" -> showNotification(context, "Пора кормить закваску!", "Через 2 часа — не забудьте!", false)
            "reminder_due" -> showNotification(context, "Пора кормить закваску! 🍶", "Самое время покормить", true)
            "reminder_urgent" -> showNotification(context, "⚠️ Закваска ждёт!", "Прошло больше 4 часов после срока!", true)
            "check" -> {
                // Compute next reminder based on config
                // For now, just show the due notification
                showNotification(context, "Пора кормить закваску! 🍶", "Самое время покормить", true)
            }
        }

        return Result.success()
    }

    private fun showNotification(context: Context, title: String, text: String, urgent: Boolean) {
        createChannel(context)

        val feedIntent = Intent(context, SourdoughNotificationReceiver::class.java).apply {
            action = "com.polinalinen.madre.FEED_SOURDOUGH"
        }
        val feedPending = PendingIntent.getBroadcast(
            context, 100, feedIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snoozeIntent = Intent(context, SourdoughNotificationReceiver::class.java).apply {
            action = "com.polinalinen.madre.SNOOZE_SOURDOUGH"
        }
        val snoozePending = PendingIntent.getBroadcast(
            context, 101, snoozeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val contentPending = PendingIntent.getActivity(
            context, 102, launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.polinalinen.madre.R.drawable.ic_bread)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(if (urgent) NotificationCompat.PRIORITY_HIGH else NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .setContentIntent(contentPending)
            .addAction(0, "Покормил ✅", feedPending)
            .addAction(0, "Отложить ⏰", snoozePending)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        private const val CHANNEL_ID = "sourdough_reminder"
        private const val NOTIFICATION_ID = 5000

        fun createChannel(context: Context) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Напоминания о закваске",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Напоминания покормить закваску"
                enableVibration(true)
            }
            manager.createNotificationChannel(channel)
        }

        fun schedule(context: Context, intervalHours: Int) {
            val workManager = WorkManager.getInstance(context)

            // Cancel existing
            workManager.cancelAllWorkByTag("sourdough_reminder")

            // Soft reminder: 2h before
            val softDelay = (intervalHours - 2).coerceAtLeast(1).toLong()
            val softWork = OneTimeWorkRequestBuilder<SourdoughReminderWorker>()
                .setInitialDelay(softDelay, TimeUnit.HOURS)
                .setInputData(workDataOf("action" to "reminder_soft"))
                .addTag("sourdough_reminder")
                .build()

            // Due reminder
            val dueWork = OneTimeWorkRequestBuilder<SourdoughReminderWorker>()
                .setInitialDelay(intervalHours.toLong(), TimeUnit.HOURS)
                .setInputData(workDataOf("action" to "reminder_due"))
                .addTag("sourdough_reminder")
                .build()

            // Urgent: 4h after due
            val urgentWork = OneTimeWorkRequestBuilder<SourdoughReminderWorker>()
                .setInitialDelay(intervalHours.toLong() + 4, TimeUnit.HOURS)
                .setInputData(workDataOf("action" to "reminder_urgent"))
                .addTag("sourdough_reminder")
                .build()

            workManager.enqueue(listOf(softWork, dueWork, urgentWork))
        }
    }
}
