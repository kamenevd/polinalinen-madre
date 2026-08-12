package com.polinalinen.madre.notifications

import com.polinalinen.madre.sourdough.StarterName

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
 * Cycle 19: у развёрнутой карточки появился свой вид — RemoteViews на бумаге
 * ([R.layout.notification_baking_progress]). Поля [headerLine], [stepLine],
 * [timerText], [nextLine] и [badge] существуют ровно для него: в самом
 * RemoteViews ничего не считается, туда кладут уже готовые строки. Это не
 * замена BigText, а слой поверх него — прошивка, которая своей карточки не
 * покажет, отдаст человеку тот же [bigText], что и раньше.
 *
 * Собирается это здесь, чистой функцией, а не внутри сервиса: в сервисе строку
 * нечем проверить, а разойтись с экраном она не имеет права.
 */
data class BakingNotificationContent(
    /** Что печётся — системный заголовок свёрнутой строки. */
    val title: String,
    /**
     * Шапка своей карточки: «Соня · Бородинский».
     *
     * Имя закваски, а не «Мадре»: закваску в семье могли переназвать, и книга
     * обязана звать её так же, как в дневнике и в колофоне. В системном
     * заголовке имени нет — там уже стоит название рецепта, и повторять его
     * второй раз незачем.
     */
    val headerLine: String,
    /** Одна строка со смыслом — то, что видно, даже если карточка свёрнута. */
    val compact: String,
    /** «Расстойка · шаг 3 из 8» — отдельной строкой своей карточки. */
    val stepLine: String,
    /** Крупные цифры карточки: «1:02:05» либо «время вышло». */
    val timerText: String,
    /** Те же цифры словами — для экранного диктора, крупные цифры он читает по знаку. */
    val spokenTimer: String,
    /** «дальше · Формовка» либо «последний шаг». Своего времени у него нет. */
    val nextLine: String,
    /** «пауза», «скоро» — либо ничего. Ярлык, а не второе число. */
    val badge: String?,
    /** Полный текст для BigTextStyle — с остатком словами и следующим шагом. */
    val bigText: String,
    /** Полоска хода — та же, что в слепке. */
    val progressPermille: Int,
    /** Отдать ли отсчёт системному хронометру. */
    val usesChronometer: Boolean,
    /** Осталось меньше пяти минут и выпечка идёт: цифры в карточке краснеют. */
    val isUrgent: Boolean,
    /** Момент, когда текущий шаг досчитает до нуля, — по стенным часам (setWhen). */
    val chronometerFinishAtMillis: Long,
    /**
     * Тот же момент по монотонным часам — база для Chronometer в своей
     * карточке. Двое часов здесь не роскошь: setWhen у уведомления понимает
     * только стенные, а Chronometer — только elapsedRealtime, и переводить одно
     * в другое на месте отрисовки значило бы считать конец шага дважды.
     */
    val chronometerBaseElapsed: Long,
) {

    companion object {

        /**
         * Последние пять минут шага. Взято не с потолка: за это время человек
         * успевает дойти до кухни и открыть духовку, и раньше торопить его
         * незачем — а после этой границы ярлык должен броситься в глаза, не
         * заставляя вчитываться в цифры.
         */
        const val URGENT_SECONDS = 300L

        /**
         * @param nowMillis «сейчас» по стенным часам — только чтобы перевести в
         *   них конец шага для setWhen.
         * @param nowElapsed «сейчас» по монотонным часам, в тот же миг, что и
         *   [nowMillis]. Оба нужны потому, что конец шага известен в монотонных,
         *   а уведомление умеет только стенные.
         */
        fun from(
            bake: BakingProgress,
            nowMillis: Long,
            nowElapsed: Long,
        ): BakingNotificationContent {
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
            // «Скоро» — только пока отсчёт идёт. На паузе торопить нечем, а на
            // нуле торопиться уже поздно: ярлык там был бы просто неправдой.
            val urgent = ticking && bake.remainingSeconds < URGENT_SECONDS
            val badge = when {
                bake.isPaused -> "пауза"
                urgent -> "скоро"
                else -> null
            }

            return BakingNotificationContent(
                title = bake.recipeName,
                headerLine = "${StarterName.sanitize(bake.starterName)} · ${bake.recipeName}",
                // Пока идёт отсчёт, время в строке не дублируется: его показывает
                // хронометр, и два числа про одну секунду разошлись бы на глазах.
                // Ярлык «скоро» — не число, и хронометру он не противоречит.
                compact = when {
                    urgent -> "$head · скоро"
                    ticking -> head
                    else -> "$head · $state"
                },
                stepLine = head,
                timerText = if (bake.remainingSeconds <= 0L) "время вышло" else time,
                spokenTimer = BakingProgress.timerLabel(bake.remainingSeconds, bake.isPaused),
                nextLine = bake.shadeNextLine(),
                badge = badge,
                bigText = "$head\n$state\n${bake.nextStepText()}",
                progressPermille = bake.permille(),
                usesChronometer = ticking,
                isUrgent = urgent,
                // Конец шага известен в монотонных часах — в стенные он
                // переводится один раз, здесь, и ровно тем сдвигом, который
                // между ними сейчас. Ни округления, ни «сейчас плюс остаток».
                chronometerFinishAtMillis =
                    if (ticking) nowMillis + (bake.stepEndsAtElapsed - nowElapsed) else nowMillis,
                chronometerBaseElapsed = if (ticking) bake.stepEndsAtElapsed else nowElapsed,
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
