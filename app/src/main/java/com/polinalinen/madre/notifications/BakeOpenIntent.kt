package com.polinalinen.madre.notifications

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.polinalinen.madre.MainActivity

/**
 * Cycle 20: дорога из шторки в свою выпечку — одна на книгу.
 *
 * Раньше её знал только foreground service, а «время вышло» и «достаньте
 * масло», которые шлёт WorkManager, не вели никуда: тап по ним не делал ничего
 * вовсе. Уведомление, зовущее что-то сделать и не открывающее места, где это
 * делают, — та же нечестная кнопка, что и в книге, только в шторке
 * (hard rule №8).
 *
 * Собрано отдельно от обоих отправителей затем, чтобы дорога у них была
 * буквально одна и та же: два места, строящие «почти такой же» Intent, рано
 * или поздно разъезжаются флагами, и одно из уведомлений начинает открывать
 * книгу поверх самой себя.
 */
object BakeOpenIntent {

    /**
     * Пока выпечка неизвестна (первые доли секунды сервиса), открывается просто
     * книга: подставлять сюда чужой id значило бы увести человека в чужую
     * выпечку, а придуманный — в несуществующую.
     */
    private const val PLACEHOLDER_SESSION_ID = 0L

    fun intent(context: Context, sessionId: Long?): Intent =
        Intent(context, MainActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            // MainActivity объявлена singleTop: книга не открывается второй раз
            // поверх себя, а приходит в onNewIntent уже открытой.
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { if (sessionId != null) putExtra(MainActivity.EXTRA_SESSION_ID, sessionId) }

    /**
     * Свой код запроса на каждую выпечку. Без него PendingIntent'ы разных
     * выпечек система считает одним и тем же, и строка второй открывает первую.
     */
    fun requestCode(sessionId: Long): Int =
        BakingNotificationContent.intentRequestCode(sessionId)

    fun pendingIntent(context: Context, sessionId: Long?): PendingIntent =
        PendingIntent.getActivity(
            context,
            requestCode(sessionId ?: PLACEHOLDER_SESSION_ID),
            intent(context, sessionId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
}
