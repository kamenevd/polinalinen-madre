package com.polinalinen.madre.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.polinalinen.madre.model.*
import org.junit.Rule
import org.junit.Test

/**
 * Compose UI tests for BakingTimelineScreen.
 * NOTE: These are instrumented tests — run with androidTest, not test.
 */
class BakingTimelineScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testRecipe = Recipe(
        id = "test-bread",
        name = "Тестовый хлеб",
        emoji = "🍞",
        description = "Для тестов",
        ingredients = emptyMap(),
        timeline = listOf(
            TimelineStep(StepType.ACTION, "Месим тесто", "Смешать все ингредиенты", 10),
            TimelineStep(StepType.WAIT, "Расстойка", "Подождать пока подойдёт", 60),
            TimelineStep(StepType.ACTION, "Формовка", "Сформовать батон", 5),
        )
    )

    private fun setTimelineContent(
        session: BakingSession = BakingSession(recipe = testRecipe),
        onAdvance: () -> Unit = {},
        onTogglePause: () -> Unit = {},
        onBack: () -> Unit = {},
        devMode: Boolean = false,
    ) {
        composeTestRule.setContent {
            BakingTimelineScreen(
                session = session,
                remainingSeconds = if (session.currentStep.type == StepType.WAIT) {
                    session.currentStep.durationMinutes * 60L
                } else 0L,
                onAdvance = onAdvance,
                onTogglePause = onTogglePause,
                onBack = onBack,
                devMode = devMode,
            )
        }
    }

    // ── Step display ─────────────────────────────────────────────

    @Test
    fun timeline_displaysRecipeName() {
        setTimelineContent()
        composeTestRule.onNodeWithText("Тестовый хлеб").assertIsDisplayed()
    }

    @Test
    fun timeline_displaysStepCounter() {
        setTimelineContent()
        composeTestRule.onNodeWithText("Шаг 1 из 3").assertIsDisplayed()
    }

    @Test
    fun timeline_displaysActionBadge() {
        setTimelineContent()
        composeTestRule.onNodeWithText("ДЕЛАЕМ").assertIsDisplayed()
    }

    @Test
    fun timeline_displaysWaitBadge() {
        val session = BakingSession(recipe = testRecipe, currentStepIndex = 1)
        setTimelineContent(session = session)
        composeTestRule.onNodeWithText("ЖДЁМ").assertIsDisplayed()
    }

    @Test
    fun timeline_displaysStepTitle() {
        setTimelineContent()
        // Title appears in both current step card and timeline overview
        composeTestRule.onAllNodesWithText("Месим тесто")[0].assertIsDisplayed()
    }

    @Test
    fun timeline_displaysStepDescription() {
        setTimelineContent()
        composeTestRule.onNodeWithText("Смешать все ингредиенты").assertIsDisplayed()
    }

    @Test
    fun timeline_displaysDurationForAction() {
        setTimelineContent()
        composeTestRule.onNodeWithText("~10 мин").assertIsDisplayed()
    }

    // ── Navigation buttons ──────────────────────────────────────

    @Test
    fun timeline_displaysNextButton() {
        setTimelineContent()
        composeTestRule.onNodeWithText("Далее →").assertIsDisplayed()
    }

    @Test
    fun timeline_displaysDoneButtonOnLastStep() {
        val session = BakingSession(recipe = testRecipe, currentStepIndex = 2)
        setTimelineContent(session = session)
        composeTestRule.onNodeWithText("Готово! 🎉").assertIsDisplayed()
    }

    @Test
    fun timeline_advanceClickTriggersCallback() {
        var advanced = false
        setTimelineContent(onAdvance = { advanced = true })
        composeTestRule.onNodeWithText("Далее →").performClick()
        assert(advanced) { "Advance should trigger callback" }
    }

    @Test
    fun timeline_backClickTriggersCallback() {
        var wentBack = false
        setTimelineContent(onBack = { wentBack = true })
        composeTestRule.onNodeWithContentDescription("Назад").performClick()
        assert(wentBack) { "Back should trigger callback" }
    }

    // ── Timeline overview ────────────────────────────────────────

    @Test
    fun timeline_displaysAllStepsSection() {
        setTimelineContent()
        composeTestRule.onNodeWithText("Все шаги").assertIsDisplayed()
    }

    @Test
    fun timeline_displaysAllStepTitles() {
        setTimelineContent()
        // Each step title appears in timeline overview
        // Use onAllNodes since titles appear in both card and timeline
        composeTestRule.onAllNodesWithText("Расстойка").assertCountEquals(1)
        composeTestRule.onAllNodesWithText("Формовка").assertCountEquals(1)
    }

    // ── Dev mode ─────────────────────────────────────────────────

    @Test
    fun devMode_showsIndicatorAndSkipButton() {
        setTimelineContent(devMode = true)
        composeTestRule.onNodeWithText("⚡ DEV ×1000").assertIsDisplayed()
        composeTestRule.onNodeWithText("Пропустить ⏭").assertIsDisplayed()
    }

    @Test
    fun devMode_hiddenByDefault() {
        setTimelineContent(devMode = false)
        composeTestRule.onNodeWithText("⚡ DEV ×1000").assertDoesNotExist()
    }

    // ── Pause for WAIT steps ─────────────────────────────────────

    @Test
    fun waitStep_showsPauseButton() {
        val session = BakingSession(recipe = testRecipe, currentStepIndex = 1)
        setTimelineContent(session = session)
        composeTestRule.onNodeWithContentDescription("Пауза").assertIsDisplayed()
    }

    @Test
    fun actionStep_noPauseButton() {
        setTimelineContent() // step 0 = ACTION
        composeTestRule.onNodeWithContentDescription("Пауза").assertDoesNotExist()
    }
}
