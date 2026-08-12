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

    /**
     * Кормление закваски — негромкий, но заметный канал.
     *
     * Cycle 18: с кнопкой «Покормила», ведущей сразу на форму кормления. Без
     * неё дорога из шторки в дневник шла через три экрана.
     */
    fun postFeedingReminder(title: String, text: String) {
        post(
            channelId = CHANNEL_SOURDOUGH,
            channelName = "Закваска",
            tag = null,
            id = ID_FEEDING,
            title = title,
            text = text,
            // Cycle 20: сам текст ведёт туда же, куда кнопка, — на форму
            // кормления. Раньше дорога была только через кнопку, а тап по
            // строке не делал ничего: половина напоминания была мёртвой.
            contentIntent = FeedingReminderAction.pendingIntent(context),
            action = NotificationCompat.Action.Builder(
                0,
                FeedingReminderAction.LABEL,
                FeedingReminderAction.pendingIntent(context),
            ).build(),
        )
    }

    /**
     * Ход выпечки: конец шага-ожидания и напоминание про масло.
     *
     * Ключ уникален по паре «сессия + шаг» и уходит в систему КАК ЕСТЬ — тегом.
     * Разные шаги не затирают друг друга, повтор того же ключа заменяет своё же
     * уведомление, и ничего считать для этого не нужно.
     */
    fun postBakingNotification(sessionId: Long, key: String, title: String, text: String) {
        post(
            channelId = CHANNEL_BAKING,
            channelName = "Выпечка",
            tag = key,
            id = ID_KEYED,
            title = title,
            text = text,
            // Cycle 20: тап открывает ИМЕННО эту выпечку. [sessionId] здесь
            // обязателен, а не «по возможности»: уведомление про шаг всегда
            // знает, чей это шаг, и молча остаться без дороги не должно.
            contentIntent = BakeOpenIntent.pendingIntent(context, sessionId),
        )
    }

    /**
     * Снять напоминание о кормлении (Cycle 20). Зовётся, когда кормление
     * записано: иначе шторка продолжает звать кормить закваску, которую только
     * что покормили, и человек идёт проверять, записалось ли.
     */
    fun cancelFeedingReminder() {
        NotificationManagerCompat.from(context).cancel(ID_FEEDING)
    }

    /**
     * Снять уведомление, показанное по этому ключу (Cycle 12). Пара «тег + id»
     * та же, что в [postBakingNotification], — снимается ровно оно.
     */
    fun cancelByKey(key: String) {
        NotificationManagerCompat.from(context).cancel(key, ID_KEYED)
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

    private fun post(
        channelId: String,
        channelName: String,
        tag: String?,
        id: Int,
        title: String,
        text: String,
        contentIntent: android.app.PendingIntent,
        action: NotificationCompat.Action? = null,
    ) {
        if (!canPost()) return
        ensureChannel(channelId, channelName)
        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_bread)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            // Тап уносит уведомление из шторки: иначе оно остаётся звать уже
            // сделанное — и звать туда, где делать больше нечего.
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .apply { if (action != null) addAction(action) }
            .build()
        notifySafely(id, notification, tag)
    }

    /**
     * [canPost] проверяет разрешение честно, но проверка живёт в отдельном
     * методе, и статический анализ этого не видит (lint MissingPermission).
     * Явная обработка SecurityException — не заглушка ради зелёного lint:
     * между проверкой и показом человек может отозвать разрешение из шторки,
     * и книга не должна падать посреди выпечки из-за уведомления.
     */
    fun notifySafely(id: Int, notification: android.app.Notification, tag: String? = null) {
        try {
            NotificationManagerCompat.from(context).notify(tag, id, notification)
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
        internal const val ID_FEEDING = 1001

        /**
         * Уведомления по ключу живут на ОДНОМ id и различаются тегом — той
         * самой строкой ключа, без единого преобразования. Система различает
         * уведомления по паре «тег + id», и пара «ключ + [ID_KEYED]» столь же
         * уникальна, сколь уникален сам ключ.
         *
         * Раньше id считался из ключа: сначала голый key.hashCode(), потом
         * floorMod по миллиону. И то и другое — отображение бесконечного
         * множества ключей в конечное, то есть совпадения в нём есть по
         * построению, а не по невезению: «step-done-1408-0» и
         * «step-done-1605-10» попадали в один и тот же 352312-й слот. Одно
         * «время вышло» вставало на место другого, а cancelByKey снимал
         * уведомление чужого шага. Считать здесь больше нечего.
         *
         * Тег непустой, поэтому пара не совпадает ни с кормлением (id 1001 без
         * тега), ни со строкой хода выпечки (2000+id сессии, тоже без тега),
         * какое бы число ни стояло рядом.
         */
        internal const val ID_KEYED = 3000
    }
}
