package com.polinalinen.madre.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for BakingSession model — core business logic.
 */
class BakingSessionTest {

    // ── Test data helpers ───────────────────────────────────────

    private fun testRecipe(
        steps: List<TimelineStep> = listOf(
            TimelineStep(StepType.ACTION, "Месим тесто", "Смешать всё", 10),
            TimelineStep(StepType.WAIT, "Ждём", "Подождать", 60),
            TimelineStep(StepType.ACTION, "Формовка", "Сформовать", 5),
        )
    ) = Recipe(
        id = "test-recipe",
        name = "Тестовый хлеб",
        emoji = "🍞",
        description = "Для тестов",
        ingredients = emptyMap(),
        timeline = steps
    )

    // ── Initial state ───────────────────────────────────────────

    @Test
    fun `initial session starts at step 0`() {
        val session = BakingSession(recipe = testRecipe())
        assertThat(session.currentStepIndex).isEqualTo(0)
        assertThat(session.isCompleted).isFalse()
        assertThat(session.isLastStep).isFalse()
    }

    @Test
    fun `initial session currentStep returns first step`() {
        val session = BakingSession(recipe = testRecipe())
        assertThat(session.currentStep.title).isEqualTo("Месим тесто")
        assertThat(session.currentStep.type).isEqualTo(StepType.ACTION)
    }

    @Test
    fun `progress at step 0 is 1 of N`() {
        val session = BakingSession(recipe = testRecipe()) // 3 steps
        assertThat(session.progress).isWithin(0.01f).of(1f / 3f)
    }

    // ── Advance ─────────────────────────────────────────────────

    @Test
    fun `advance moves to next step`() {
        val session = BakingSession(recipe = testRecipe())
        val next = session.advance()
        assertThat(next.currentStepIndex).isEqualTo(1)
        assertThat(next.currentStep.title).isEqualTo("Ждём")
    }

    @Test
    fun `advance resets pause state`() {
        val paused = BakingSession(recipe = testRecipe(), isPaused = true)
        val next = paused.advance()
        assertThat(next.isPaused).isFalse()
    }

    @Test
    fun `advance to last step sets isLastStep true`() {
        val session = BakingSession(recipe = testRecipe(), currentStepIndex = 1)
        val next = session.advance()
        assertThat(next.isLastStep).isTrue()
        assertThat(next.currentStepIndex).isEqualTo(2)
    }

    @Test
    fun `advance past last step marks completed`() {
        val session = BakingSession(recipe = testRecipe(), currentStepIndex = 2)
        val completed = session.advance()
        assertThat(completed.isCompleted).isTrue()
        assertThat(completed.completedAt).isNotNull()
    }

    @Test
    fun `advance does not increment step index on completion`() {
        val session = BakingSession(recipe = testRecipe(), currentStepIndex = 2)
        val completed = session.advance()
        assertThat(completed.currentStepIndex).isEqualTo(2)
    }

    // ── Toggle pause ────────────────────────────────────────────

    @Test
    fun `togglePause flips pause state`() {
        val session = BakingSession(recipe = testRecipe())
        assertThat(session.isPaused).isFalse()

        val paused = session.togglePause()
        assertThat(paused.isPaused).isTrue()

        val resumed = paused.togglePause()
        assertThat(resumed.isPaused).isFalse()
    }

    // ── Total duration ──────────────────────────────────────────

    @Test
    fun `totalDurationMinutes property sums all steps`() {
        val session = BakingSession(recipe = testRecipe()) // 10 + 60 + 5 = 75
        assertThat(session.totalDurationMinutes).isEqualTo(75)
    }

    @Test
    fun `totalDurationMinutes matches recipe timeline sum`() {
        val session = BakingSession(recipe = testRecipe())
        assertThat(session.totalDurationMinutes).isEqualTo(
            session.recipe.timeline.sumOf { it.durationMinutes }
        )
    }

    // ── Progress ────────────────────────────────────────────────

    @Test
    fun `progress advances correctly through all steps`() {
        val recipe = testRecipe() // 3 steps
        var session = BakingSession(recipe = recipe)
        assertThat(session.progress).isWithin(0.01f).of(1f / 3f)

        session = session.advance() // step 1
        assertThat(session.progress).isWithin(0.01f).of(2f / 3f)

        session = session.advance() // step 2
        assertThat(session.progress).isWithin(0.01f).of(1f)
    }

    // ── Edge cases ──────────────────────────────────────────────

    @Test
    fun `single step recipe completes on first advance`() {
        val recipe = testRecipe(steps = listOf(
            TimelineStep(StepType.ACTION, "Единственный шаг", "Всё", 5)
        ))
        val session = BakingSession(recipe = recipe)
        assertThat(session.isLastStep).isTrue()

        val completed = session.advance()
        assertThat(completed.isCompleted).isTrue()
    }

    @Test
    fun `empty timeline returns progress 0`() {
        val recipe = testRecipe(steps = emptyList())
        val session = BakingSession(recipe = recipe)
        assertThat(session.progress).isEqualTo(0f)
    }

    // ── advance() on completed session is no-op ────────────────

    @Test
    fun `advance on completed session returns same instance`() {
        val recipe = testRecipe() // 3 steps
        var session = BakingSession(recipe = recipe)
        repeat(3) { session = session.advance() }
        assertThat(session.isCompleted).isTrue()

        val beforeAdvance = session
        val afterAdvance = session.advance()
        // advance() on completed returns this (no-op)
        assertThat(afterAdvance.isCompleted).isTrue()
        assertThat(afterAdvance.currentStepIndex).isEqualTo(beforeAdvance.currentStepIndex)
        assertThat(afterAdvance.completedAt).isEqualTo(beforeAdvance.completedAt)
    }

    // ── Empty timeline edge case ────────────────────────────────

    @Test
    fun `currentStep on empty timeline returns fallback`() {
        val recipe = testRecipe(steps = emptyList())
        val session = BakingSession(recipe = recipe)
        // No longer crashes — returns fallback TimelineStep
        val step = session.currentStep
        assertThat(step.title).isEqualTo("—")
        assertThat(step.durationMinutes).isEqualTo(0)
    }

    @Test
    fun `empty timeline has zero progress and is not completed`() {
        val recipe = testRecipe(steps = emptyList())
        val session = BakingSession(recipe = recipe)
        assertThat(session.progress).isEqualTo(0f)
        assertThat(session.isCompleted).isFalse()
        assertThat(session.currentStepIndex).isEqualTo(0)
    }

    // ── Full recipe walkthrough (Хлебушек домашний pattern) ──────

    @Test
    fun `complete walkthrough through all 11 steps`() {
        val recipe = testRecipe(steps = List(11) { i ->
            TimelineStep(
                type = if (i % 2 == 0) StepType.ACTION else StepType.WAIT,
                title = "Шаг ${i + 1}",
                description = "Описание ${i + 1}",
                durationMinutes = (i + 1) * 5
            )
        })

        var session = BakingSession(recipe = recipe)
        assertThat(session.currentStepIndex).isEqualTo(0)
        assertThat(session.isCompleted).isFalse()

        repeat(11) {
            session = session.advance()
        }

        assertThat(session.isCompleted).isTrue()
        assertThat(session.completedAt).isNotNull()
    }
}
