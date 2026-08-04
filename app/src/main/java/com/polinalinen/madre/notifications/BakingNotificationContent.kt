package com.polinalinen.madre.notifications

/**
 * Cycle 14: всё, что человек читает в шторке, пока идёт выпечка.
 *
 * ЧЕСТНО ПРО ANDROID. Разворачивать ли карточку в шторке, решает система и
 * человек, а не приложение: программного способа «держать уведомление
 * раскрытым» нет, и книга такого не обещает. Обещает она ровно три вещи, и все
 * три проверяются юнит-тестом:
 *
 *  · свёрнутая строка сама по себе имеет смысл — шаг и его номер стоят в
 *    начале, а не в хвосте, который система обрежет;
 *  · обратный отсчёт виден всегда — его рисует системный хронометр
 *    (setWhen + setUsesChronometer + setChronometerCountDown), поэтому он тикает
 *    и между обновлениями, и в свёрнутой карточке;
 *  · в BigText лежит полный текст, включая остаток словами, — так ничего не
 *    зависит от одного лишь хронометра.
 *
 * Собирается это здесь, чистой функцией, а не внутри сервиса: в сервисе строку
 * нечем проверить, а разойтись с экраном она не имеет права.
 */
data class BakingNotificationContent(
    /** Что печётся. */
    val title: String,
    /** Одна строка со смыслом — то, что видно, даже если карточка свёрнута. */
    val compact: String,
    /** Полный текст для BigTextStyle — с остатком словами и следующим шагом. */
    val bigText: String,
    /** Полоска хода — та же, что в слепке. */
    val progressPermille: Int,
    /** Отдать ли отсчёт системному хронометру. */
    val usesChronometer: Boolean,
    /** Момент, когда текущий шаг досчитает до нуля. */
    val chronometerFinishAtMillis: Long,
) {

    companion object {

        fun from(bake: BakingProgress, nowMillis: Long): BakingNotificationContent {
            // Хронометр имеет смысл, только когда есть чему идти: на паузе он
            // врал бы, а на нуле мигал бы отрицательным временем.
            val ticking = !bake.isPaused && bake.remainingSeconds > 0L
            val head = "${bake.stepTitle} · шаг ${bake.stepIndex + 1} из ${bake.stepCount}"
            val time = BakingProgress.formatRemaining(bake.remainingSeconds)
            val state = when {
                bake.isPaused -> "пауза, осталось $time"
                bake.remainingSeconds <= 0L -> "время вышло"
                else -> "осталось $time"
            }

            return BakingNotificationContent(
                title = bake.recipeName,
                // Пока идёт отсчёт, время в строке не дублируется: его показывает
                // хронометр, и два числа про одну секунду разошлись бы на глазах.
                compact = if (ticking) head else "$head · $state",
                bigText = "$head\n$state\n${bake.nextStepText()}",
                progressPermille = bake.permille(),
                usesChronometer = ticking,
                chronometerFinishAtMillis =
                    if (ticking) nowMillis + bake.remainingSeconds * 1000L else nowMillis,
            )
        }

        /**
         * Свой requestCode на каждую выпечку. Без него PendingIntent'ы разных
         * выпечек система считает одним и тем же, и вторая строка в шторке
         * открывает первую. Диапазон общий с id уведомлений — он уже заведомо
         * не пересекается с напоминанием о кормлении.
         */
        fun intentRequestCode(sessionId: Long): Int = BakingProgress.notificationId(sessionId)
    }
}
