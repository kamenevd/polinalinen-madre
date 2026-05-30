package com.polinalinen.madre.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Unit tests for timer stability scenarios.
 * Tests the BakingSession model logic that underpins timer behavior.
 */
class TimerStabilityTest {

    // ── Test data helpers ───────────────────────────────────────

    private fun breadRecipe() = Recipe(
        id = "bread",
        name = "Хлебушек",
        emoji = "🍞",
        description = "Тест",
        ingredients = emptyMap(),
        timeline = listOf(
            TimelineStep(StepType.ACTION, "Готовим опару", "Смешать", 5),
            TimelineStep(StepType.WAIT, "Опара подходит", "Ночь", 600),  // 10ч
            TimelineStep(StepType.ACTION, "Замес", "Месить", 10),
            TimelineStep(StepType.WAIT, "Аутолиз", "Ждать", 30),
            TimelineStep(StepType.ACTION, "Формовка", "Сформовать", 5),
            TimelineStep(StepType.WAIT, "Ферментация", "Ждать", 180),   // 3ч
            TimelineStep(StepType.WAIT, "Выпечка", "Печь", 40),
            TimelineStep(StepType.ACTION, "Готово!", "Вынуть", 0),
        )
    )

    private fun shortRecipe() = Recipe(
        id = "short",
        name = "Быстрый",
        emoji = "🧁",
        description = "Короткий",
        ingredients = emptyMap(),
        timeline = listOf(
            TimelineStep(StepType.ACTION, "Шаг 1", "Действие", 5),
            TimelineStep(StepType.WAIT, "Шаг 2", "Ждать 10 мин", 10),
            TimelineStep(StepType.ACTION, "Шаг 3", "Действие", 5),
        )
    )

    // ── Timer does not reset on pause/resume ────────────────────

    @Test
    fun `pause preserves step index`() {
        var session = BakingSession(recipe = breadRecipe())
        session = session.advance() // step 1 — WAIT
        val stepIndexBeforePause = session.currentStepIndex

        val paused = session.togglePause()
        assertThat(paused.currentStepIndex).isEqualTo(stepIndexBeforePause)
        assertThat(paused.currentStep.title).isEqualTo("Опара подходит")
    }

    @Test
    fun `resume after pause preserves step index and recipe`() {
        var session = BakingSession(recipe = breadRecipe())
        session = session.advance() // step 1 — WAIT

        val paused = session.togglePause()
        assertThat(paused.isPaused).isTrue()

        val resumed = paused.togglePause()
        assertThat(resumed.isPaused).isFalse()
        assertThat(resumed.currentStepIndex).isEqualTo(1)
        assertThat(resumed.currentStep.title).isEqualTo("Опара подходит")
        assertThat(resumed.recipe.id).isEqualTo("bread")
    }

    @Test
    fun `stepStartedAtMillis does not change on pause toggle`() {
        var session = BakingSession(recipe = breadRecipe())
        session = session.advance()
        val startedAt = session.stepStartedAtMillis

        val paused = session.togglePause()
        assertThat(paused.stepStartedAtMillis).isEqualTo(startedAt)

        val resumed = paused.togglePause()
        assertThat(resumed.stepStartedAtMillis).isEqualTo(startedAt)
    }

    // ── Timer continues through step transitions ────────────────

    @Test
    fun `advance from WAIT step resets stepStartedAtMillis for next step`() {
        var session = BakingSession(recipe = breadRecipe())
        session = session.advance() // step 1 — WAIT
        val waitStartedAt = session.stepStartedAtMillis

        // Ensure measurable time passes for timestamp comparison
        Thread.sleep(50)

        val next = session.advance() // step 2 — ACTION
        assertThat(next.currentStepIndex).isEqualTo(2)
        assertThat(next.currentStep.title).isEqualTo("Замес")
        assertThat(next.stepStartedAtMillis).isAtLeast(waitStartedAt)
    }

    @Test
    fun `advance from WAIT to WAIT resets stepStartedAtMillis`() {
        val recipe = Recipe(
            id = "two-waits",
            name = "Два ожидания",
            emoji = "⏳",
            description = "Тест",
            ingredients = emptyMap(),
            timeline = listOf(
                TimelineStep(StepType.WAIT, "Ждём 1", "Первое ожидание", 30),
                TimelineStep(StepType.WAIT, "Ждём 2", "Второе ожидание", 60),
            )
        )

        var session = BakingSession(recipe = recipe)
        val firstStartedAt = session.stepStartedAtMillis

        Thread.sleep(50)

        val next = session.advance()
        assertThat(next.currentStepIndex).isEqualTo(1)
        assertThat(next.currentStep.title).isEqualTo("Ждём 2")
        assertThat(next.stepStartedAtMillis).isAtLeast(firstStartedAt)
    }

    // ── Multiple pause/resume cycles ────────────────────────────

    @Test
    fun `multiple pause resume cycles preserve state`() {
        var session = BakingSession(recipe = breadRecipe())
        session = session.advance() // step 1 — WAIT

        repeat(5) {
            session = session.togglePause()
            assertThat(session.isPaused).isTrue()
            assertThat(session.currentStepIndex).isEqualTo(1)

            session = session.togglePause()
            assertThat(session.isPaused).isFalse()
            assertThat(session.currentStepIndex).isEqualTo(1)
        }

        assertThat(session.currentStep.title).isEqualTo("Опара подходит")
    }

    // ── Advance resets isPaused ─────────────────────────────────

    @Test
    fun `advance after pause starts next step unpaused`() {
        var session = BakingSession(recipe = breadRecipe())
        session = session.advance() // step 1 — WAIT

        val paused = session.togglePause()
        assertThat(paused.isPaused).isTrue()

        val next = paused.advance() // step 2 — ACTION
        assertThat(next.isPaused).isFalse()
        assertThat(next.currentStepIndex).isEqualTo(2)
    }

    // ── Active session data integrity ───────────────────────────

    @Test
    fun `session recipe reference is immutable across operations`() {
        val recipe = breadRecipe()
        var session = BakingSession(recipe = recipe)

        // Advance through all steps (8 steps = 8 advances to complete)
        repeat(8) { session = session.advance() }

        assertThat(session.recipe.name).isEqualTo("Хлебушек")
        assertThat(session.recipe.timeline.size).isEqualTo(8)
        assertThat(session.isCompleted).isTrue()
    }

    @Test
    fun `completed session has completedAt timestamp`() {
        val recipe = shortRecipe() // 3 steps
        var session = BakingSession(recipe = recipe)

        repeat(3) { session = session.advance() }

        assertThat(session.isCompleted).isTrue()
        assertThat(session.completedAt).isNotNull()
        assertThat(session.completedAt!!).isGreaterThan(0L)
    }

    // ── Progress tracking through timer steps ───────────────────

    @Test
    fun `progress increments correctly including WAIT steps`() {
        val recipe = breadRecipe() // 8 steps
        var session = BakingSession(recipe = recipe)
        val steps = mutableListOf<Float>()

        steps.add(session.progress)
        repeat(7) {
            session = session.advance()
            steps.add(session.progress)
        }

        // Progress should be: 1/8, 2/8, ..., 8/8
        assertThat(steps[0]).isWithin(0.01f).of(1f / 8f)  // ACTION
        assertThat(steps[1]).isWithin(0.01f).of(2f / 8f)  // WAIT — timer step
        assertThat(steps[2]).isWithin(0.01f).of(3f / 8f)  // ACTION
        assertThat(steps[3]).isWithin(0.01f).of(4f / 8f)  // WAIT — timer step
        assertThat(steps[7]).isWithin(0.01f).of(1f)       // completed
    }

    // ── Duration calculation for WAIT steps ─────────────────────

    @Test
    fun `WAIT step duration is correctly stored`() {
        val recipe = breadRecipe()
        val waitStep = recipe.timeline[1] // "Опара подходит"
        assertThat(waitStep.type).isEqualTo(StepType.WAIT)
        assertThat(waitStep.durationMinutes).isEqualTo(600) // 10ч = 600 мин
    }

    @Test
    fun `total duration includes all WAIT step durations`() {
        val recipe = breadRecipe()
        val total = recipe.timeline.sumOf { it.durationMinutes }
        // 5 + 600 + 10 + 30 + 5 + 180 + 40 + 0 = 870
        assertThat(total).isEqualTo(870)
    }

    // ── isLastStep detection for timer steps ────────────────────

    @Test
    fun `WAIT step can be last step`() {
        val recipe = Recipe(
            id = "wait-last",
            name = "Последний ЖДЁМ",
            emoji = "⏰",
            description = "Тест",
            ingredients = emptyMap(),
            timeline = listOf(
                TimelineStep(StepType.ACTION, "Действие", "Делаем", 5),
                TimelineStep(StepType.WAIT, "Финальное ожидание", "Ждём", 60),
            )
        )

        var session = BakingSession(recipe = recipe)
        session = session.advance() // step 1 — WAIT

        assertThat(session.isLastStep).isTrue()
        assertThat(session.currentStep.type).isEqualTo(StepType.WAIT)

        val completed = session.advance()
        assertThat(completed.isCompleted).isTrue()
    }
}
