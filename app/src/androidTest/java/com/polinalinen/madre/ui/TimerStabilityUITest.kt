package com.polinalinen.madre.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.polinalinen.madre.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * UI tests for timer stability in BakingTimelineScreen.
 * Tests that timer-related UI elements behave correctly during
 * pause/resume, step transitions, and screen state changes.
 */
class TimerStabilityUITest {

    @get:Rule
    val rule = createComposeRule()

    // ── Test data ───────────────────────────────────────────────

    private val waitRecipe = Recipe(
        id = "wait-test",
        name = "Таймер-тест",
        emoji = "⏱️",
        description = "Рецепт для проверки таймера",
        ingredients = emptyMap(),
        timeline = listOf(
            TimelineStep(StepType.ACTION, "Подготовка", "Подготовить", 5),
            TimelineStep(StepType.WAIT, "Ожидание", "Ждать 30 мин", 30),
            TimelineStep(StepType.ACTION, "Завершение", "Закончить", 5),
        )
    )

    private val multiWaitRecipe = Recipe(
        id = "multi-wait",
        name = "Несколько таймеров",
        emoji = "⏳",
        description = "Рецепт с несколькими ЖДЁМ шагами",
        ingredients = emptyMap(),
        timeline = listOf(
            TimelineStep(StepType.WAIT, "Ждём 1", "10 мин", 10),
            TimelineStep(StepType.ACTION, "Действие", "Делаем", 5),
            TimelineStep(StepType.WAIT, "Ждём 2", "20 мин", 20),
            TimelineStep(StepType.WAIT, "Ждём 3", "5 мин", 5),
        )
    )

    private fun render(
        session: BakingSession,
        remainingSeconds: Long = 0L,
        onAdvance: () -> Unit = {},
        onBack: () -> Unit = {},
        onTogglePause: () -> Unit = {},
        devMode: Boolean = false
    ) {
        rule.setContent {
            BakingTimelineScreen(
                session = session,
                remainingSeconds = remainingSeconds,
                onAdvance = onAdvance,
                onBack = onBack,
                onTogglePause = onTogglePause,
                devMode = devMode
            )
        }
    }

    // ── WAIT step shows correct badge ───────────────────────────

    @Test
    fun WAIT_step_displays_ZHDEM_badge() {
        val session = BakingSession(recipe = waitRecipe, currentStepIndex = 1)
        render(session = session, remainingSeconds = 30 * 60L)

        rule.onNodeWithText("ЖДЁМ").assertExists()
        rule.onAllNodesWithText("Ожидание").assertCountEquals(2)
    }

    @Test
    fun ACTION_step_displays_DELAEM_badge() {
        val session = BakingSession(recipe = waitRecipe, currentStepIndex = 0)
        render(session = session)

        rule.onNodeWithText("ДЕЛАЕМ").assertExists()
        rule.onAllNodesWithText("Подготовка").assertCountEquals(2)
    }

    // ── Pause button visibility ─────────────────────────────────

    @Test
    fun WAIT_step_shows_pause_button() {
        val session = BakingSession(recipe = waitRecipe, currentStepIndex = 1)
        render(session = session, remainingSeconds = 30 * 60L)

        rule.onNodeWithContentDescription("Пауза").assertExists()
    }

    @Test
    fun paused_WAIT_step_shows_resume_button() {
        val session = BakingSession(recipe = waitRecipe, currentStepIndex = 1, isPaused = true)
        render(session = session, remainingSeconds = 30 * 60L)

        rule.onNodeWithContentDescription("Продолжить").assertExists()
        rule.onNodeWithText("ПАУЗА").assertExists()
    }

    @Test
    fun ACTION_step_no_pause_button() {
        val session = BakingSession(recipe = waitRecipe, currentStepIndex = 0)
        render(session = session)

        rule.onNodeWithContentDescription("Пауза").assertDoesNotExist()
    }

    // ── Step counter shows correct progress ─────────────────────

    @Test
    fun step_counter_current_out_of_total_WAIT() {
        val session = BakingSession(recipe = waitRecipe, currentStepIndex = 1)
        render(session = session, remainingSeconds = 30 * 60L)

        rule.onNodeWithText("Шаг 2 из 3").assertExists()
    }

    @Test
    fun step_counter_shows_last_step() {
        val session = BakingSession(recipe = waitRecipe, currentStepIndex = 2)
        render(session = session)

        rule.onNodeWithText("Шаг 3 из 3").assertExists()
    }

    // ── Multi-WAIT recipe timeline ──────────────────────────────

    @Test
    fun multi_WAIT_recipe_shows_all_steps() {
        val session = BakingSession(recipe = multiWaitRecipe, currentStepIndex = 0)
        render(session = session, remainingSeconds = 10 * 60L)

        rule.onNodeWithText("Ждём 2").assertExists()
        rule.onNodeWithText("Ждём 3").assertExists()
    }

    // ── Timer display format ────────────────────────────────────

    @Test
    fun timer_displays_minutes_seconds() {
        val session = BakingSession(recipe = waitRecipe, currentStepIndex = 1)
        render(session = session, remainingSeconds = 25 * 60L + 30L) // 25:30

        rule.onNodeWithText("25:30").assertExists()
    }

    @Test
    fun timer_displays_hours_format() {
        val recipe = Recipe(
            id = "long-wait", name = "Долгое ожидание", emoji = "🕐",
            description = "Тест", ingredients = emptyMap(),
            timeline = listOf(TimelineStep(StepType.WAIT, "Ночное ожидание", "10 часов", 600))
        )
        val session = BakingSession(recipe = recipe, currentStepIndex = 0)
        render(session = session, remainingSeconds = 9 * 3600L + 59 * 60L + 52L)

        rule.onNodeWithText("9:59:52").assertExists()
    }

    // ── Dev mode indicator ──────────────────────────────────────

    @Test
    fun dev_mode_indicator_shows_when_enabled() {
        val session = BakingSession(recipe = waitRecipe, currentStepIndex = 1)
        render(session = session, remainingSeconds = 30 * 60L, devMode = true)

        rule.onNodeWithText("⚡ DEV ×1000").assertExists()
    }

    @Test
    fun dev_mode_indicator_hidden() {
        val session = BakingSession(recipe = waitRecipe, currentStepIndex = 1)
        render(session = session, remainingSeconds = 30 * 60L, devMode = false)

        rule.onNodeWithText("⚡ DEV ×1000").assertDoesNotExist()
    }

    // ── Callbacks fire correctly during timer ───────────────────

    @Test
    fun onTogglePause_callback_fires_for_WAIT_step() {
        var pauseClicked = false
        val session = BakingSession(recipe = waitRecipe, currentStepIndex = 1)
        render(
            session = session,
            remainingSeconds = 30 * 60L,
            onTogglePause = { pauseClicked = true }
        )

        rule.onNodeWithContentDescription("Пауза").performClick()
        assertTrue("Pause callback should fire", pauseClicked)
    }

    @Test
    fun onAdvance_callback_fires_during_WAIT_step() {
        var advanceClicked = false
        val session = BakingSession(recipe = waitRecipe, currentStepIndex = 1)
        render(
            session = session,
            remainingSeconds = 30 * 60L,
            onAdvance = { advanceClicked = true }
        )

        rule.onNodeWithText("Далее →").performClick()
        assertTrue("Advance callback should fire", advanceClicked)
    }

    // ── Step transition keeps timer state consistent ─────────────

    @Test
    fun after_advancing_from_WAIT_badge_changes_to_DELAEM() {
        val session = BakingSession(recipe = waitRecipe, currentStepIndex = 2)
        render(session = session)

        rule.onNodeWithText("ДЕЛАЕМ").assertExists()
        rule.onAllNodesWithText("Завершение").assertCountEquals(2)
        rule.onNodeWithContentDescription("Пауза").assertDoesNotExist()
    }
}
