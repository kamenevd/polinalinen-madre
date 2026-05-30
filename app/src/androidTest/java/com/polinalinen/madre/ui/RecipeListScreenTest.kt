package com.polinalinen.madre.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.polinalinen.madre.model.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

/**
 * Compose UI tests for RecipeListScreen.
 * NOTE: These are instrumented tests — run with androidTest, not test.
 */
class RecipeListScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private val testRecipes = listOf(
        Recipe(
            id = "khlebushek",
            name = "Хлебушек домашний",
            emoji = "🍞",
            description = "Домашний хлеб на закваске",
            ingredients = mapOf("Sponge" to listOf("30 г закваски")),
            timeline = List(11) { i ->
                TimelineStep(
                    type = if (i % 2 == 0) StepType.ACTION else StepType.WAIT,
                    title = "Шаг ${i + 1}",
                    description = "Описание шага ${i + 1}",
                    durationMinutes = (i + 1) * 10
                )
            }
        ),
        Recipe(
            id = "pirozhki",
            name = "Пирожки",
            emoji = "🥧",
            description = "Тесто для пирожков на закваске",
            ingredients = emptyMap(),
            timeline = listOf(
                TimelineStep(StepType.ACTION, "Месим", "Описание", 10),
                TimelineStep(StepType.WAIT, "Ждём", "Описание", 60),
            )
        )
    )

    @Test
    fun recipeList_displaysHeader() {
        composeTestRule.setContent {
            RecipeListScreen(recipes = testRecipes, onRecipeClick = {})
        }

        composeTestRule.onNodeWithText("Levito Madre").assertIsDisplayed()
        composeTestRule.onNodeWithText("Печём дома с любовью").assertIsDisplayed()
    }

    @Test
    fun recipeList_displaysAllRecipeNames() {
        composeTestRule.setContent {
            RecipeListScreen(recipes = testRecipes, onRecipeClick = {})
        }

        composeTestRule.onNodeWithText("Хлебушек домашний").assertIsDisplayed()
        composeTestRule.onNodeWithText("Пирожки").assertIsDisplayed()
    }

    @Test
    fun recipeCard_showsStepCount() {
        composeTestRule.setContent {
            RecipeListScreen(recipes = testRecipes, onRecipeClick = {})
        }

        composeTestRule.onNodeWithText("11 шагов").assertIsDisplayed()
    }

    @Test
    fun recipeCard_clickTriggersCallback() {
        var clickedRecipe: Recipe? = null

        composeTestRule.setContent {
            RecipeListScreen(
                recipes = testRecipes,
                onRecipeClick = { clickedRecipe = it }
            )
        }

        composeTestRule.onNodeWithText("Хлебушек домашний").performClick()
        assertNotNull("Recipe click should trigger callback", clickedRecipe)
        assertEquals("khlebushek", clickedRecipe!!.id)
    }
}
