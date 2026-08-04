package com.polinalinen.madre.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Cycle 14: «на сколько печём» — единственный источник масштаба.
 *
 * Здесь проверяется всё, что рецепт обязан сказать про выбранное количество
 * порций: во сколько раз растут граммы, каким получится выход, влезает ли это
 * в одну духовку и меняются ли от порций времена этапов (не меняются — и книга
 * говорит об этом вслух, а не молчит).
 */
class RecipeScaleTest {

    private fun flour(grams: Double) =
        Ingredient(name = "мука", amount = grams, unit = "г", category = "flour", isFlour = true)

    private fun water(grams: Double) =
        Ingredient(name = "вода", amount = grams, unit = "г", category = "water")

    private fun recipeOf(vararg ingredients: Ingredient): Recipe = Recipe(
        id = "test",
        name = "Test",
        emoji = "",
        description = "",
        ingredients = mapOf("main" to ingredients.toList()),
        timeline = listOf(TimelineStep(StepType.WAIT, "Расстойка", "", 120)),
    )

    @Test
    fun `portions outside the shelf snap back onto it`() {
        assertThat(RecipeScale.clampPortions(0)).isEqualTo(RecipeScale.MIN_PORTIONS)
        assertThat(RecipeScale.clampPortions(-3)).isEqualTo(RecipeScale.MIN_PORTIONS)
        assertThat(RecipeScale.clampPortions(99)).isEqualTo(RecipeScale.MAX_PORTIONS)
        assertThat(RecipeScale.clampPortions(3)).isEqualTo(3)
    }

    @Test
    fun `the scale factor is exactly the number of portions`() {
        assertThat(RecipeScale.factor(1)).isEqualTo(1.0)
        assertThat(RecipeScale.factor(3)).isEqualTo(3.0)
        // И тоже не выходит за полку — иначе ингредиенты уехали бы дальше подписи.
        assertThat(RecipeScale.factor(42)).isEqualTo(RecipeScale.MAX_PORTIONS.toDouble())
    }

    @Test
    fun `the yield grows with the portions, in whole grams`() {
        val recipe = recipeOf(flour(500.0), water(350.0))
        assertThat(RecipeScale.yieldGrams(recipe, 1)).isEqualTo(850)
        assertThat(RecipeScale.yieldGrams(recipe, 3)).isEqualTo(2550)
    }

    @Test
    fun `a recipe with nothing weighable claims no yield at all`() {
        val recipe = recipeOf(
            Ingredient(name = "соль", amount = 0.0, unit = "по вкусу", category = "salt", scalable = false),
        )
        assertThat(RecipeScale.yieldGrams(recipe, 2)).isEqualTo(0)
        assertThat(RecipeScale.yieldText(recipe, 2)).isNull()
    }

    @Test
    fun `the yield line says grams of dough for the chosen portions`() {
        val recipe = recipeOf(flour(500.0), water(350.0))
        assertThat(RecipeScale.yieldText(recipe, 2)).isEqualTo("выход ≈ 1700 г теста")
    }

    /**
     * Главное обещание фичи: выход пересчитывается вместе с ингредиентами, а не
     * остаётся с прошлого выбора порций.
     */
    @Test
    fun `the yield never lags a portion behind the ingredients`() {
        val recipe = recipeOf(flour(500.0), water(350.0))
        (1..RecipeScale.MAX_PORTIONS).forEach { n ->
            val sumOfIngredients = recipe.ingredients.getValue("main")
                .sumOf { Math.round(it.amount * RecipeScale.factor(n)) }
            assertThat(RecipeScale.yieldGrams(recipe, n).toLong()).isEqualTo(sumOfIngredients)
        }
    }

    @Test
    fun `a batch that fits the oven says nothing about batches`() {
        val recipe = recipeOf(flour(500.0), water(350.0))
        assertThat(RecipeScale.capacityNote(recipe, 1)).isNull()
    }

    @Test
    fun `a batch too big for one oven says so, with the real numbers`() {
        val recipe = recipeOf(flour(500.0), water(350.0))
        // 850 г × 3 = 2550 г — в домашнюю духовку за раз столько не входит.
        val note = RecipeScale.capacityNote(recipe, 3)
        assertThat(note).isNotNull()
        assertThat(note).contains("2550")
        assertThat(note).contains("${RecipeScale.OVEN_BATCH_GRAMS}")
        assertThat(note).contains("2 захода")
    }

    @Test
    fun `the number of batches is honest arithmetic, not a guess`() {
        val recipe = recipeOf(flour(500.0), water(350.0))
        assertThat(RecipeScale.batches(recipe, 1)).isEqualTo(1)
        assertThat(RecipeScale.batches(recipe, 2)).isEqualTo(2) // 1700 > 1500
        assertThat(RecipeScale.batches(recipe, 5)).isEqualTo(3) // 4250 -> 3 захода
    }

    @Test
    fun `an empty recipe still needs exactly one batch, never zero`() {
        val recipe = recipeOf(
            Ingredient(name = "соль", amount = 0.0, unit = "по вкусу", category = "salt", scalable = false),
        )
        assertThat(RecipeScale.batches(recipe, 4)).isEqualTo(1)
        assertThat(RecipeScale.capacityNote(recipe, 4)).isNull()
    }

    /**
     * Порции меняют граммы — и только их. Про времена книга говорит прямо,
     * чтобы никто не искал, почему расстойка «не выросла втрое».
     */
    @Test
    fun `the book says out loud that timings do not follow the portions`() {
        assertThat(RecipeScale.TIMING_NOTE).contains("не меняется")
        assertThat(RecipeScale.TIMING_NOTE).isNotEmpty()
    }

    @Test
    fun `planned time is the same at every portion count`() {
        val recipe = recipeOf(flour(500.0), water(350.0))
        val minutes = (1..RecipeScale.MAX_PORTIONS).map { RecipeScale.totalMinutes(recipe, it) }.distinct()
        assertThat(minutes).containsExactly(120)
    }
}
