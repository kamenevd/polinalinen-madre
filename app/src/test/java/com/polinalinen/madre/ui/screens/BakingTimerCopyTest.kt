package com.polinalinen.madre.ui.screens

import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.notifications.BakingNotificationContent
import org.junit.Test

/**
 * Cycle 20: что написано на странице таймера, когда время вышло, — и когда
 * страница считает, что пора торопиться.
 *
 * До этого цикла страница на нуле показывала «0:00» и подпись «тесто спит. не
 * будите его.»: два утверждения, из которых первое — цифра ни о чём, а второе
 * прямо неверно, шаг уже кончился. В шторке в ту же секунду стояло «время
 * вышло». Одно событие, два разных ответа в двух местах книги.
 *
 * Второе: «скоро» на странице наступало за десятую часть шага (у трёхчасовой
 * расстойки — за 18 минут), а в шторке — за пять минут. Порог теперь один на
 * книгу, и он живёт там же, где о нём написано, —
 * [BakingNotificationContent.URGENT_SECONDS].
 */
class BakingTimerCopyTest {

    @Test
    fun `a step that ran out says so instead of showing a zero`() {
        assertThat(BakingTimerCopy.timerText(0)).isEqualTo("время вышло")
        assertThat(BakingTimerCopy.timerText(0)).doesNotContain("0:00")
    }

    /** Отрицательный остаток в книгу не попадает, но минус на 88sp — не вариант. */
    @Test
    fun `a negative remainder never becomes a minus sign on the page`() {
        assertThat(BakingTimerCopy.timerText(-1)).isEqualTo("время вышло")
        assertThat(BakingTimerCopy.timerText(-3_725)).doesNotContain("-")
    }

    @Test
    fun `while the step runs the page shows the same digits as always`() {
        assertThat(BakingTimerCopy.timerText(3_725)).isEqualTo("1:02:05")
        assertThat(BakingTimerCopy.timerText(125)).isEqualTo("2:05")
        assertThat(BakingTimerCopy.timerText(1)).isEqualTo("0:01")
    }

    /** Слова вместо цифр не помещаются в 88sp — у них свой кегль. */
    @Test
    fun `the words get their own smaller size, the digits keep the big one`() {
        assertThat(BakingTimerCopy.timerSizeSp(0)).isLessThan(BakingTimerCopy.timerSizeSp(600))
        assertThat(BakingTimerCopy.timerSizeSp(600)).isEqualTo(88f)
    }

    // ————— «скоро» —————

    @Test
    fun `the page hurries at the same five minutes the shade hurries at`() {
        val threshold = BakingNotificationContent.URGENT_SECONDS
        assertThat(threshold).isEqualTo(300L)
        assertThat(BakingTimerCopy.isUrgent(threshold, isPaused = false)).isFalse()
        assertThat(BakingTimerCopy.isUrgent(threshold - 1, isPaused = false)).isTrue()
    }

    /**
     * Порог не зависит от длины шага. Раньше зависел: десятая часть трёхчасовой
     * расстойки — восемнадцать минут, и страница краснела там, где шторка ещё
     * молчала.
     */
    @Test
    fun `a long step does not get its own longer hurry`() {
        assertThat(BakingTimerCopy.isUrgent(18 * 60L, isPaused = false)).isFalse()
    }

    @Test
    fun `nothing is urgent on pause or after the time is out`() {
        assertThat(BakingTimerCopy.isUrgent(30, isPaused = true)).isFalse()
        assertThat(BakingTimerCopy.isUrgent(0, isPaused = false)).isFalse()
        assertThat(BakingTimerCopy.isUrgent(-5, isPaused = false)).isFalse()
    }

    // ————— подпись-настроение —————

    @Test
    fun `the mood line never says the dough is sleeping once the step is over`() {
        val mood = BakingTimerCopy.moodLine(isWait = true, isPaused = false, remainingSeconds = 0)
        assertThat(mood).contains("время вышло")
        assertThat(mood).doesNotContain("спит")
    }

    /** Пауза после конца шага — это всё ещё конец шага, а не «страница заложена». */
    @Test
    fun `a bake paused past the end still says the time is out`() {
        assertThat(BakingTimerCopy.moodLine(isWait = true, isPaused = true, remainingSeconds = 0))
            .contains("время вышло")
    }

    @Test
    fun `a running bake keeps the moods it had`() {
        assertThat(BakingTimerCopy.moodLine(isWait = true, isPaused = true, remainingSeconds = 600))
            .isEqualTo("страница заложена. вернёмся, когда скажешь")
        assertThat(BakingTimerCopy.moodLine(isWait = true, isPaused = false, remainingSeconds = 60))
            .isEqualTo("почти! не уходи далеко")
        assertThat(BakingTimerCopy.moodLine(isWait = true, isPaused = false, remainingSeconds = 3_600))
            .isEqualTo("тесто спит. не будите его.")
        assertThat(BakingTimerCopy.moodLine(isWait = false, isPaused = false, remainingSeconds = 3_600))
            .isEqualTo("твой ход, пекарь")
    }
}
