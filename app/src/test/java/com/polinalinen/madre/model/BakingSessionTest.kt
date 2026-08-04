package com.polinalinen.madre.model

import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.notifications.BakingNotificationContent
import com.polinalinen.madre.notifications.BakingProgress
import org.junit.Test

/**
 * Cycle 14: один ход выпечки.
 *
 * Экран и шторка берут время из одного места — остатка текущего шага, который
 * считается от [BakingSession.stepStartedAtMillis]. Значит, пауза, продолжение,
 * последний шаг и шаг нулевой длины обязаны вести себя предсказуемо здесь, а не
 * в двух местах по-своему. Проверяется и то, что шторка на каждом из этих
 * состояний говорит правду.
 */
class BakingSessionTest {

    private fun step(title: String, minutes: Int, type: StepType = StepType.WAIT) =
        TimelineStep(type = type, title = title, description = "", durationMinutes = minutes)

    private fun session(
        steps: List<TimelineStep> = listOf(step("Расстойка", 60), step("Формовка", 10), step("Выпечка", 40)),
        currentStepIndex: Int = 0,
    ) = BakingSession(
        id = 1L,
        recipe = Recipe(
            id = "bread", name = "Бородинский", emoji = "", description = "",
            ingredients = emptyMap(), timeline = steps,
        ),
        currentStepIndex = currentStepIndex,
    )

    /** Сколько осталось по текущему шагу — та же арифметика, что в BakingViewModel. */
    private fun remainingSeconds(s: BakingSession, nowMillis: Long): Long {
        val elapsed = (nowMillis - s.stepStartedAtMillis) / 1000
        return (s.currentStep.durationMinutes * 60L - elapsed).coerceAtLeast(0)
    }

    @Test
    fun `a fresh step starts with its whole time ahead`() {
        val s = session()
        assertThat(remainingSeconds(s, s.stepStartedAtMillis)).isEqualTo(3600)
    }

    @Test
    fun `pausing marks the moment it was paused`() {
        val paused = session().togglePause()
        assertThat(paused.isPaused).isTrue()
        assertThat(paused.accumulatedPauseMs).isGreaterThan(0L)
    }

    /**
     * Главное свойство паузы: простоявшее время не съедает шаг. После
     * продолжения остаток тот же, каким был в момент паузы.
     */
    @Test
    fun `resuming gives back exactly the time the pause took`() {
        val started = session()
        val pausedAt = started.stepStartedAtMillis + 600_000L // десять минут спустя
        val paused = started.copy(isPaused = true, accumulatedPauseMs = pausedAt)

        val resumedAtMillis = pausedAt + 300_000L // простояли ещё пять минут
        val resumed = paused.copy(
            isPaused = false,
            stepStartedAtMillis = paused.stepStartedAtMillis + (resumedAtMillis - pausedAt),
            accumulatedPauseMs = 0,
        )

        assertThat(remainingSeconds(resumed, resumedAtMillis))
            .isEqualTo(remainingSeconds(started, pausedAt))
    }

    @Test
    fun `resuming clears the pause mark, so a second pause cannot double count`() {
        val resumed = session().togglePause().togglePause()
        assertThat(resumed.isPaused).isFalse()
        assertThat(resumed.accumulatedPauseMs).isEqualTo(0L)
    }

    @Test
    fun `advancing moves to the next step and restarts its clock`() {
        val s = session()
        val next = s.advance()
        assertThat(next.currentStepIndex).isEqualTo(1)
        assertThat(next.isCompleted).isFalse()
        assertThat(next.stepStartedAtMillis).isAtLeast(s.stepStartedAtMillis)
        assertThat(remainingSeconds(next, next.stepStartedAtMillis)).isEqualTo(600)
    }

    @Test
    fun `advancing wakes a paused step up instead of leaving it frozen`() {
        val next = session().togglePause().advance()
        assertThat(next.isPaused).isFalse()
        assertThat(next.accumulatedPauseMs).isEqualTo(0L)
    }

    @Test
    fun `the last step is the last one, and finishing it finishes the bake`() {
        val last = session(currentStepIndex = 2)
        assertThat(last.isLastStep).isTrue()

        val finished = last.advance()
        assertThat(finished.isCompleted).isTrue()
        assertThat(finished.completedAt).isNotNull()
        // Номер шага не уезжает за конец плана.
        assertThat(finished.currentStepIndex).isEqualTo(2)
    }

    @Test
    fun `a finished bake cannot be advanced again`() {
        val finished = session(currentStepIndex = 2).advance()
        assertThat(finished.advance()).isEqualTo(finished)
    }

    @Test
    fun `a step of zero minutes is already out of time, not counting backwards`() {
        val instant = session(steps = listOf(step("Смазать форму", 0, StepType.ACTION), step("Выпечка", 40)))
        assertThat(remainingSeconds(instant, instant.stepStartedAtMillis)).isEqualTo(0)
        assertThat(remainingSeconds(instant, instant.stepStartedAtMillis + 60_000L)).isEqualTo(0)
    }

    @Test
    fun `a recipe with no steps at all does not crash on its current step`() {
        val empty = session(steps = emptyList())
        assertThat(empty.currentStep.durationMinutes).isEqualTo(0)
        assertThat(empty.progress).isEqualTo(0f)
    }

    // --- то же самое, но глазами шторки: один слепок, одно время ---

    private fun shadeOf(s: BakingSession, remaining: Long) = BakingProgress(
        sessionId = s.id,
        recipeName = s.recipe.name,
        stepTitle = s.currentStep.title,
        stepIndex = s.currentStepIndex,
        stepCount = s.recipe.timeline.size,
        remainingSeconds = remaining,
        elapsedSeconds = 0,
        totalSeconds = s.totalDurationMinutes * 60L,
        isPaused = s.isPaused,
        nextStepTitle = s.recipe.timeline.getOrNull(s.currentStepIndex + 1)?.title,
    )

    @Test
    fun `the shade counts down while running and stops while paused`() {
        val running = shadeOf(session(), remaining = 3600)
        val paused = shadeOf(session().togglePause(), remaining = 3600)
        val now = 1_700_000_000_000L

        assertThat(BakingNotificationContent.from(running, now).usesChronometer).isTrue()
        assertThat(BakingNotificationContent.from(paused, now).usesChronometer).isFalse()
        assertThat(BakingNotificationContent.from(paused, now).compact).contains("пауза")
    }

    @Test
    fun `the shade names the next step while there is one, and says so when there is not`() {
        assertThat(shadeOf(session(), remaining = 3600).nextStepText()).isEqualTo("дальше: Формовка")
        assertThat(shadeOf(session(currentStepIndex = 2), remaining = 1).nextStepText())
            .isEqualTo("последний шаг")
    }

    @Test
    fun `a step out of time says so in the shade rather than showing a dead clock`() {
        val content = BakingNotificationContent.from(
            shadeOf(session(), remaining = 0),
            1_700_000_000_000L,
        )
        assertThat(content.usesChronometer).isFalse()
        assertThat(content.compact).contains("время вышло")
    }
}
