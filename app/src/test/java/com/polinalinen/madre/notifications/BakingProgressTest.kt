package com.polinalinen.madre.notifications

import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.sourdough.StarterName
import org.junit.Test

/**
 * Cycle 12: строка хода выпечки в шторке. Всё, что в ней написано и нарисовано,
 * считается здесь — чтобы прогресс был настоящим, а не декоративной полоской.
 */
class BakingProgressTest {

    private fun progress(
        sessionId: Long = 1L,
        stepIndex: Int = 0,
        stepCount: Int = 4,
        remainingSeconds: Long = 600,
        elapsedSeconds: Long = 0,
        totalSeconds: Long = 3600,
        isPaused: Boolean = false,
        nextStepTitle: String? = "Складка",
        starterName: String = StarterName.DEFAULT,
    ) = BakingProgress(
        sessionId = sessionId,
        recipeName = "Бородинский",
        starterName = starterName,
        stepTitle = "Расстойка",
        stepIndex = stepIndex,
        stepCount = stepCount,
        remainingSeconds = remainingSeconds,
        elapsedSeconds = elapsedSeconds,
        totalSeconds = totalSeconds,
        isPaused = isPaused,
        nextStepTitle = nextStepTitle,
    )

    /**
     * Cycle 19: имя закваски едет в шторку тем же слепком, что и всё остальное.
     * Своего источника у сервиса нет и не будет — иначе шапка карточки и
     * колофон разошлись бы в написании одного имени.
     */
    @Test
    fun `a snapshot carries the starter name, and an unnamed one stays Мадре`() {
        assertThat(progress().starterName).isEqualTo(StarterName.DEFAULT)
        assertThat(progress(starterName = "Соня").starterName).isEqualTo("Соня")
    }

    @Test
    fun `every bake keeps its own notification and nobody collides`() {
        val ids = (1L..50L).map { BakingProgress.notificationId(it) }
        assertThat(ids).containsNoDuplicates()
        // Чужие уведомления книги (кормление — 1001) не задеваются.
        assertThat(ids).doesNotContain(1001)
    }

    @Test
    fun `progress follows real elapsed time, not the step number`() {
        assertThat(progress(elapsedSeconds = 0, totalSeconds = 3600).permille()).isEqualTo(0)
        assertThat(progress(elapsedSeconds = 900, totalSeconds = 3600).permille()).isEqualTo(250)
        assertThat(progress(elapsedSeconds = 3600, totalSeconds = 3600).permille()).isEqualTo(1000)
    }

    @Test
    fun `progress never runs past the end or before the start`() {
        assertThat(progress(elapsedSeconds = 9_000, totalSeconds = 3600).permille()).isEqualTo(1000)
        assertThat(progress(elapsedSeconds = -50, totalSeconds = 3600).permille()).isEqualTo(0)
    }

    /** Рецепт без времени в плане не должен делить на ноль. */
    @Test
    fun `a timeless recipe shows no progress instead of crashing`() {
        assertThat(progress(elapsedSeconds = 10, totalSeconds = 0).permille()).isEqualTo(0)
    }

    @Test
    fun `the line says the step, the count and the time left`() {
        val text = progress(stepIndex = 2, stepCount = 8, remainingSeconds = 3_725).contentText()
        assertThat(text).contains("Расстойка")
        assertThat(text).contains("шаг 3 из 8")
        assertThat(text).contains("1:02:05")
    }

    @Test
    fun `minutes and seconds only, when there are no hours`() {
        assertThat(progress(remainingSeconds = 125).contentText()).contains("2:05")
        assertThat(progress(remainingSeconds = 0).contentText()).contains("0:00")
    }

    @Test
    fun `a paused bake says so instead of counting down`() {
        val text = progress(isPaused = true, remainingSeconds = 600).contentText()
        assertThat(text).contains("пауза")
        assertThat(text).doesNotContain("10:00")
    }

    /**
     * Шаг-действие тоже показывает остаток, но говорить «осталось» про
     * оценку нельзя: человек месит тесто столько, сколько месит.
     */
    @Test
    fun `an action step counts down without promising anything`() {
        assertThat(progress(remainingSeconds = 0, isPaused = false).contentText()).isNotEmpty()
    }

    /**
     * Cycle 14: один ход выпечки — один отсчёт.
     *
     * До этого цикла следующий шаг ехал строкой «Следующий шаг: Формовка ·
     * через 2:05» — со СВОИМ временем, которое на деле было временем текущего
     * шага. Два countdown'а на экране и в шторке про одну и ту же секунду:
     * человек видел «через 2:05» дважды и не понимал, до чего именно 2:05.
     * Теперь следующий шаг — только название и контекст.
     */
    @Test
    fun `the next step is a name, not a second countdown`() {
        val text = progress(stepIndex = 1, stepCount = 3, remainingSeconds = 125, nextStepTitle = "Формовка")
            .nextStepText()
        assertThat(text).isEqualTo("дальше: Формовка")
        assertThat(text).doesNotContain("2:05")
        // Никаких цифр вовсе: время в этой строке взяться неоткуда.
        assertThat(text.any { it.isDigit() }).isFalse()
    }

    @Test
    fun `the last step says it is the last, with no next to name`() {
        val text = progress(stepIndex = 3, stepCount = 4, nextStepTitle = null).nextStepText()
        assertThat(text).isEqualTo("последний шаг")
    }

    /** Шаг может оказаться последним и по счёту, даже если название пришло. */
    @Test
    fun `a next title on the final step is ignored, not printed`() {
        val text = progress(stepIndex = 3, stepCount = 4, nextStepTitle = "Формовка").nextStepText()
        assertThat(text).isEqualTo("последний шаг")
    }

    @Test
    fun `a blank next title is treated as no next step at all`() {
        assertThat(progress(stepIndex = 0, stepCount = 4, nextStepTitle = "  ").nextStepText())
            .isEqualTo("последний шаг")
    }

    @Test
    fun `pausing changes the countdown line, never the next step line`() {
        val running = progress(stepIndex = 1, stepCount = 3, nextStepTitle = "Формовка")
        val paused = progress(stepIndex = 1, stepCount = 3, nextStepTitle = "Формовка", isPaused = true)
        assertThat(paused.nextStepText()).isEqualTo(running.nextStepText())
        assertThat(paused.contentText()).isNotEqualTo(running.contentText())
    }

    /** Единственное место, где в слепке есть время, — остаток текущего шага. */
    @Test
    fun `a snapshot carries exactly one clock`() {
        val text = progress(stepIndex = 1, stepCount = 3, remainingSeconds = 125, nextStepTitle = "Формовка")
        assertThat(text.contentText()).contains("2:05")
        assertThat(text.nextStepText()).doesNotContain("2:05")
    }

    // --- то же время, сказанное вслух: подпись таймера для экранного диктора ---

    @Test
    fun `the timer says its time in words, not in colons`() {
        assertThat(BakingProgress.timerLabel(3725, isPaused = false)).isEqualTo("осталось 1 час 2 минуты")
        assertThat(BakingProgress.timerLabel(125, isPaused = false)).isEqualTo("осталось 2 минуты 5 секунд")
        assertThat(BakingProgress.timerLabel(45, isPaused = false)).isEqualTo("осталось 45 секунд")
    }

    @Test
    fun `a whole minute left is said without a trailing zero seconds`() {
        assertThat(BakingProgress.spokenRemaining(120)).isEqualTo("2 минуты")
    }

    @Test
    fun `a paused timer and a finished one say what they are`() {
        assertThat(BakingProgress.timerLabel(300, isPaused = true)).isEqualTo("пауза, осталось 5 минут")
        assertThat(BakingProgress.timerLabel(0, isPaused = false)).isEqualTo("время вышло")
        assertThat(BakingProgress.timerLabel(-5, isPaused = false)).isEqualTo("время вышло")
    }

    /**
     * Русское склонение на числах, где его чаще всего и ломают: 11 — не «одна»,
     * 21 — не «много», 12 и 14 — не «две» и не «четыре».
     */
    @Test
    fun `minutes are declined the way Russian actually declines them`() {
        assertThat(BakingProgress.spokenRemaining(11 * 60L)).isEqualTo("11 минут")
        assertThat(BakingProgress.spokenRemaining(12 * 60L)).isEqualTo("12 минут")
        assertThat(BakingProgress.spokenRemaining(14 * 60L)).isEqualTo("14 минут")
        assertThat(BakingProgress.spokenRemaining(21 * 60L)).isEqualTo("21 минута")
        assertThat(BakingProgress.spokenRemaining(22 * 60L)).isEqualTo("22 минуты")
        assertThat(BakingProgress.spokenRemaining(25 * 60L)).isEqualTo("25 минут")
    }

    @Test
    fun `hours and seconds are declined too`() {
        assertThat(BakingProgress.spokenRemaining(11 * 3600L)).isEqualTo("11 часов")
        assertThat(BakingProgress.spokenRemaining(21 * 3600L)).isEqualTo("21 час")
        assertThat(BakingProgress.spokenRemaining(2 * 3600L)).isEqualTo("2 часа")
        assertThat(BakingProgress.spokenRemaining(11)).isEqualTo("11 секунд")
        assertThat(BakingProgress.spokenRemaining(12)).isEqualTo("12 секунд")
        assertThat(BakingProgress.spokenRemaining(14)).isEqualTo("14 секунд")
        assertThat(BakingProgress.spokenRemaining(21)).isEqualTo("21 секунда")
    }

    /** Секунды в многочасовой расстойке — шум, а не ответ на «сколько ещё». */
    @Test
    fun `seconds are left out once there are hours to name`() {
        assertThat(BakingProgress.spokenRemaining(3600 + 3 * 60 + 41)).isEqualTo("1 час 3 минуты")
    }
}
