package com.polinalinen.madre.ui.screens

import com.polinalinen.madre.notifications.BakingNotificationContent
import com.polinalinen.madre.notifications.BakingProgress

/**
 * Cycle 20: что написано на странице таймера — отдельно от того, как оно
 * нарисовано.
 *
 * Вынесено из [BakingTimerScreen] не ради красоты. Страница и шторка говорят об
 * одной и той же секунде, и до этого цикла говорили по-разному: на нуле
 * страница показывала «0:00» и подпись «тесто спит. не будите его.» — цифру ни
 * о чём и утверждение, прямо неверное, шаг уже кончился, — а в шторке в ту же
 * секунду стояло «время вышло». Порог «скоро» тоже был свой: десятая часть
 * шага против пяти минут. Внутри composable это нечем проверить; здесь —
 * есть чем ([BakingTimerCopyTest]).
 *
 * Числовой формат и слова для диктора не дублируются, а берутся из
 * [BakingProgress]: у книги один способ произнести остаток.
 */
object BakingTimerCopy {

    /** Кегль крупных цифр — тех, ради которых страница и заведена. */
    const val DIGITS_SP = 88f

    /**
     * Слова вместо цифр — своим кеглем: «время вышло» на 88sp не помещается в
     * строку ни на одном телефоне, а переносить крупный отсчёт на вторую
     * строку значит ронять всё, что под ним.
     */
    const val WORDS_SP = 34f

    /**
     * Ноль — это не «0:00», а конец шага, и сказано это должно быть теми же
     * словами, что в шторке. Отрицательный остаток в книгу не попадает
     * ([BakingProgress.formatRemaining] его и так зажимает), но минус на 88sp
     * не должен быть возможен даже теоретически.
     */
    fun timerText(remainingSeconds: Long): String =
        if (remainingSeconds <= 0L) "время вышло"
        else BakingProgress.formatRemaining(remainingSeconds)

    fun timerSizeSp(remainingSeconds: Long): Float =
        if (remainingSeconds <= 0L) WORDS_SP else DIGITS_SP

    /**
     * «Скоро» — общий для книги порог [BakingNotificationContent.URGENT_SECONDS],
     * а не десятая часть шага: у трёхчасовой расстойки десятая часть — это
     * восемнадцать минут, и страница краснела там, где шторка ещё молчала.
     *
     * На паузе и на нуле не торопим: в первом случае торопить нечем, во втором
     * уже поздно.
     */
    fun isUrgent(remainingSeconds: Long, isPaused: Boolean): Boolean =
        !isPaused && remainingSeconds > 0L &&
            remainingSeconds < BakingNotificationContent.URGENT_SECONDS

    /**
     * Подпись-настроение под цифрами. Конец шага проверяется первым — и раньше
     * паузы: выпечка, поставленная на паузу после конца шага, — это всё ещё
     * конец шага, а не «страница заложена».
     */
    fun moodLine(isWait: Boolean, isPaused: Boolean, remainingSeconds: Long): String = when {
        remainingSeconds <= 0L -> "время вышло — можно дальше"
        isPaused -> "страница заложена. вернёмся, когда скажешь"
        isUrgent(remainingSeconds, isPaused) -> "почти! не уходи далеко"
        isWait -> "тесто спит. не будите его."
        else -> "твой ход, пекарь"
    }
}
