package com.polinalinen.madre.notifications

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.polinalinen.madre.R

/**
 * Cycle 11: единственное место, где книга действительно трогает notification
 * API. Раньше разрешение POST_NOTIFICATIONS спрашивали, а показывать было
 * нечем — receiver в манифесте так и остался закомментированным.
 *
 * Ни статики, ни глобального mutable-состояния: экземпляр создаётся владельцем
 * (Application / Worker) от своего Context. Канал заводится лениво перед
 * показом — дешевле, чем городить инициализацию в Application, и переживает
 * «Очистить данные» без отдельной ветки.
 *
 * [post] тихо ничего не делает, если разрешения нет: на Android 13+ человек
 * вправе отказать, и это не ошибка приложения.
 */
class MadreNotifier(private val context: Context) {

    /** Кормление закваски — негромкий, но заметный канал. */
    fun postFeedingReminder(title: String, text: String) {
        post(CHANNEL_SOURDOUGH, "Закваска", ID_FEEDING, title, text)
    }

    /** Ход выпечки: конец шага-ожидания и напоминание про масло. */
    fun postBakingNotification(key: String, title: String, text: String) {
        // Ключ уникален по паре «сессия + шаг», поэтому уведомления о разных
        // шагах не затирают друг друга, а повтор того же — заменяет, не плодит.
        post(CHANNEL_BAKING, "Выпечка", key.hashCode(), title, text)
    }

    /**
     * true, если книге вообще позволено показывать уведомления. Проверяется
     * ДО любого обращения к NotificationManager — на API 33+ без гранта
     * notify() молча отбрасывается системой, и притворяться, что напоминание
     * доставлено, было бы нечестно.
     */
    fun canPost(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private fun post(channelId: String, channelName: String, id: Int, title: String, text: String) {
        if (!canPost()) return
        ensureChannel(channelId, channelName)
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_bread)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        notifySafely(id, notification)
    }

    /**
     * [canPost] проверяет разрешение честно, но проверка живёт в отдельном
     * методе, и статический анализ этого не видит (lint MissingPermission).
     * Явная обработка SecurityException — не заглушка ради зелёного lint:
     * между проверкой и показом человек может отозвать разрешение из шторки,
     * и книга не должна падать посреди выпечки из-за уведомления.
     */
    fun notifySafely(id: Int, notification: android.app.Notification) {
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Разрешение отозвали прямо сейчас — молчим, как и без него.
        }
    }

    private fun ensureChannel(channelId: String, channelName: String) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(channelId) != null) return
        manager.createNotificationChannel(
            NotificationChannel(channelId, channelName, NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    companion object {
        const val CHANNEL_SOURDOUGH = "madre_sourdough"
        const val CHANNEL_BAKING = "madre_baking"

        // Напоминание о кормлении всегда одно — новое заменяет прежнее.
        private const val ID_FEEDING = 1001
    }
}
