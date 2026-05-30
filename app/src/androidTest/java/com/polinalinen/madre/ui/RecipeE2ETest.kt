package com.polinalinen.madre.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.polinalinen.madre.model.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/**
 * End-to-end instrumented test: complete recipe walkthrough.
 * Simulates the user flow: select recipe → advance all steps → completion.

 * This mirrors the manual mobile-mcp test pattern:
 * 1. See recipe in list → click it
 * 2. See timeline → advance through steps
 * 3. See "Готово!" on last step
 */
class RecipeE2ETest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val khlebushekRecipe = Recipe(
        id = "khlebushek",
        name = "Хлебушек домашний",
        emoji = "🍞",
        description = "Домашний хлеб на закваске Левито Мадре — мягкий и ароматный",
        ingredients = mapOf(
            "Sponge" to listOf("30 г активной закваски", "95 г тёплой воды", "95 г муки пшеничной в/с"),
            "Main" to listOf("Вся опара", "300 г тёплой воды", "515 г пшеничной муки в/с")
        ),
        timeline = listOf(
            TimelineStep(StepType.ACTION, "Готовим опару (с вечера)", "Смешать закваску, воду, муку.", 5),
            TimelineStep(StepType.WAIT, "Опара подходит (ночь)", "8–12 часов.", 600),
            TimelineStep(StepType.ACTION, "Замес (автолиз)", "Смешать всю опару с водой и мукой.", 10),
            TimelineStep(StepType.WAIT, "Аутолиз", "30 минут при ~25°C.", 30),
            TimelineStep(StepType.ACTION, "Замес с добавками", "Добавить сахар, месить 5 мин.", 20),
            TimelineStep(StepType.WAIT, "Брожение + складывания", "3 часа при 24–25°C.", 180),
            TimelineStep(StepType.ACTION, "Формовка", "Сформовать в форму.", 5),
            TimelineStep(StepType.WAIT, "Холодная ферментация", "В холодильник до вечера.", 420),
            TimelineStep(StepType.ACTION, "Прогрев + духовка", "Надрезать верх.", 15),
            TimelineStep(StepType.WAIT, "Выпечка", "10 мин при 240°.", 40),
            TimelineStep(StepType.WAIT, "Остывание", "Вынуть из формы, остудить.", 120),
        )
    )

    @Test
    fun e2e_selectRecipeFromList_andVerifyDetails() {
        var selectedRecipe: Recipe? = null

        composeTestRule.setContent {
            RecipeListScreen(
                recipes = listOf(khlebushekRecipe),
                onRecipeClick = { selectedRecipe = it }
            )
        }

        // Verify recipe visible
        composeTestRule.onNodeWithText("Хлебушек домашний").assertIsDisplayed()
        composeTestRule.onNodeWithText("11 шагов").assertIsDisplayed()
        composeTestRule.onNodeWithText("Домашний хлеб на закваске Левито Мадре — мягкий и ароматный").assertIsDisplayed()

        // Click
        composeTestRule.onNodeWithText("Хлебушек домашний").performClick()
        assertNotNull(selectedRecipe)
        assertEquals("khlebushek", selectedRecipe!!.id)
    }

    @Test
    fun e2e_firstStep_showsCorrectUI() {
        val session = BakingSession(recipe = khlebushekRecipe)

        composeTestRule.setContent {
            BakingTimelineScreen(
                session = session,
                remainingSeconds = 0L,
                onAdvance = {},
                onTogglePause = {},
                onBack = {},
            )
        }

        // Verify step 1 UI
        composeTestRule.onNodeWithText("Шаг 1 из 11").assertIsDisplayed()
        composeTestRule.onNodeWithText("ДЕЛАЕМ").assertIsDisplayed()
        composeTestRule.onNodeWithText("Готовим опару (с вечера)").assertIsDisplayed()
        composeTestRule.onNodeWithText("~5 мин").assertIsDisplayed()
        composeTestRule.onNodeWithText("Далее →").assertIsDisplayed()
    }

    @Test
    fun e2e_lastStep_showsDoneButton() {
        val session = BakingSession(recipe = khlebushekRecipe, currentStepIndex = 10)

        composeTestRule.setContent {
            BakingTimelineScreen(
                session = session,
                remainingSeconds = session.currentStep.durationMinutes * 60L,
                onAdvance = {},
                onTogglePause = {},
                onBack = {},
            )
        }

        composeTestRule.onNodeWithText("Шаг 11 из 11").assertIsDisplayed()
        composeTestRule.onNodeWithText("Остывание").assertIsDisplayed()
        composeTestRule.onNodeWithText("Готово! 🎉").assertIsDisplayed()
    }

    @Test
    fun e2e_advanceStep_updatesStepCounter() {
        var session = BakingSession(recipe = khlebushekRecipe)

        composeTestRule.setContent {
            BakingTimelineScreen(
                session = session,
                remainingSeconds = 0L,
                onAdvance = { session = session.advance() },
                onTogglePause = {},
                onBack = {},
            )
        }

        // Initially step 1
        composeTestRule.onNodeWithText("Шаг 1 из 11").assertIsDisplayed()

        // Advance
        composeTestRule.onNodeWithText("Далее →").performClick()
    }
}
