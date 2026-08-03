package com.polinalinen.madre.notifications

import com.google.common.truth.Truth.assertThat
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
        nextStepSeconds: Long? = remainingSeconds,
    ) = BakingProgress(
        sessionId = sessionId,
        recipeName = "Бородинский",
        stepTitle = "Расстойка",
        stepIndex = stepIndex,
        stepCount = stepCount,
        remainingSeconds = remainingSeconds,
        elapsedSeconds = elapsedSeconds,
        totalSeconds = totalSeconds,
        isPaused = isPaused,
        nextStepTitle = nextStepTitle,
        nextStepSeconds = nextStepSeconds,
    )

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

    @Test
    fun `running progress names the next step and uses current remaining time`() {
        val text = progress(stepIndex = 1, stepCount = 3, remainingSeconds = 125, nextStepTitle = "Формовка").etaText()
        assertThat(text).isEqualTo("Следующий шаг: Формовка · через 2:05")
    }

    @Test
    fun `paused progress keeps the same remaining time in the shared eta`() {
        val text = progress(isPaused = true, remainingSeconds = 125, nextStepTitle = "Формовка").etaText()
        assertThat(text).isEqualTo("Следующий шаг: Формовка · через 2:05")
    }

    @Test
    fun `last step says when the whole bake ends`() {
        val text = progress(stepIndex = 3, stepCount = 4, remainingSeconds = 0, nextStepTitle = null, nextStepSeconds = null).etaText()
        assertThat(text).isEqualTo("Выпечка завершится через 0:00")
    }

    @Test
    fun `zero time uses zero for both next step and final bake`() {
        assertThat(progress(remainingSeconds = 0, nextStepSeconds = 0).etaText()).contains("через 0:00")
        assertThat(progress(stepIndex = 3, stepCount = 4, remainingSeconds = 0, nextStepTitle = null).etaText()).contains("0:00")
    }
}
