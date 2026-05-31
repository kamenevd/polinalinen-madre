package com.polinalinen.madre.sourdough

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SourdoughNotificationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            "com.polinalinen.madre.FEED_SOURDOUGH" -> {
                // Record feeding — use SharedPreferences for simple storage
                // The ViewModel will pick this up on next refresh
                val prefs = context.getSharedPreferences("sourdough_prefs", Context.MODE_PRIVATE)
                prefs.edit().putLong("last_notification_feeding", System.currentTimeMillis()).apply()

                // Cancel notification
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                manager.cancel(5000)

                // Reschedule reminders (needs new interval from config)
                // Use default 72h for now — will be corrected on app open
                SourdoughReminderWorker.schedule(context, 72)
            }
            "com.polinalinen.madre.SNOOZE_SOURDOUGH" -> {
                // Cancel current notification
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                manager.cancel(5000)

                // Reschedule in 4 hours
                val workManager = androidx.work.WorkManager.getInstance(context)
                val snoozeWork = androidx.work.OneTimeWorkRequestBuilder<SourdoughReminderWorker>()
                    .setInitialDelay(4, java.util.concurrent.TimeUnit.HOURS)
                    .setInputData(androidx.work.workDataOf("action" to "reminder_due"))
                    .addTag("sourdough_reminder")
                    .build()
                workManager.enqueue(snoozeWork)
            }
        }
    }
}
