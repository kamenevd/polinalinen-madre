package com.polinalinen.madre.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Baker's-percentage math — портировано без изменений из v3 (см. RecipeScaler.kt),
 * но тестов на него никогда не было ни в v3, ни в v4. Цифры ниже подобраны так,
 * чтобы проверить именно арифметику, а не конкретный рецепт из recipes.json.
 */
class RecipeScalerTest {

    private fun flour(name: String, grams: Double) =
        Ingredient(name = name, amount = grams, unit = "г", category = "flour", isFlour = true)

    private fun water(grams: Double) =
        Ingredient(name = "вода", amount = grams, unit = "г", category = "water")

    private fun recipeOf(vararg ingredients: Ingredient, sectionName: String = "main"): Recipe =
        Recipe(
            id = "test",
            name = "Test",
            emoji = "",
            description = "",
            ingredients = mapOf(sectionName to ingredients.toList()),
            timeline = emptyList(),
        )

    @Test
    fun `totalFlourGrams sums only isFlour ingredients across all sections`() {
        val recipe = Recipe(
            id = "t", name = "t", emoji = "", description = "",
            ingredients = mapOf(
                "sponge" to listOf(flour("мука опары", 100.0), water(100.0)),
                "main" to listOf(flour("мука теста", 400.0), water(250.0)),
            ),
            timeline = emptyList(),
        )
        assertThat(RecipeScaler.totalFlourGrams(recipe)).isEqualTo(500.0)
    }

    @Test
    fun `totalFlourGrams is zero when recipe has no flour`() {
        val recipe = recipeOf(water(300.0))
        assertThat(RecipeScaler.totalFlourGrams(recipe)).isEqualTo(0.0)
    }

    @Test
    fun `totalDoughGrams excludes non-scalable and referenced ingredients`() {
        val recipe = recipeOf(
            flour("мука", 500.0),
            water(300.0),
            Ingredient(name = "по вкусу соль", amount = 0.0, unit = "по вкусу", category = "salt", scalable = false),
            Ingredient(
                name = "вся опара", amount = 0.0, unit = "г", category = "ref",
                refType = "all_of_section", refSection = "sponge",
            ),
        )
        // Только мука (500) + вода (300) — соль нескейлируемая, опара — ссылка на другую секцию.
        assertThat(RecipeScaler.totalDoughGrams(recipe)).isEqualTo(800.0)
    }

    @Test
    fun `bakerPercentage expresses ingredient as percent of total flour`() {
        val recipe = recipeOf(flour("мука", 500.0), water(350.0))
        val water = recipe.ingredients.getValue("main")[1]
        assertThat(RecipeScaler.bakerPercentage(water, recipe)).isWithin(0.001).of(70.0)
    }

    @Test
    fun `bakerPercentage is zero when recipe has no flour to divide by`() {
        val recipe = recipeOf(water(300.0))
        val water = recipe.ingredients.getValue("main")[0]
        assertThat(RecipeScaler.bakerPercentage(water, recipe)).isEqualTo(0.0)
    }

    @Test
    fun `scaleByFlour returns ratio of target to current total flour`() {
        val recipe = recipeOf(flour("мука", 500.0))
        // Хотим напечь на 750г муки вместо 500г — коэффициент 1.5.
        assertThat(RecipeScaler.scaleByFlour(recipe, 750.0)).isWithin(0.0001).of(1.5)
    }

    @Test
    fun `scaleByFlour defaults to 1 when recipe has no flour`() {
        val recipe = recipeOf(water(300.0))
        assertThat(RecipeScaler.scaleByFlour(recipe, 999.0)).isEqualTo(1.0)
    }

    @Test
    fun `scaleByTotalWeight returns ratio of target to current dough weight`() {
        val recipe = recipeOf(flour("мука", 500.0), water(300.0))
        assertThat(RecipeScaler.scaleByTotalWeight(recipe, 1600.0)).isWithin(0.0001).of(2.0)
    }

    @Test
    fun `scaleByTotalWeight defaults to 1 when dough weight is zero`() {
        val recipe = recipeOf(
            Ingredient(name = "соль", amount = 0.0, unit = "по вкусу", category = "salt", scalable = false),
        )
        assertThat(RecipeScaler.scaleByTotalWeight(recipe, 999.0)).isEqualTo(1.0)
    }

    @Test
    fun `scaledDisplayText leaves non-scalable ingredients untouched`() {
        val salt = Ingredient(name = "соль по вкусу", amount = 0.0, unit = "по вкусу", category = "salt", scalable = false)
        assertThat(RecipeScaler.scaledDisplayText(salt, scaleFactor = 3.0)).isEqualTo("соль по вкусу")
    }

    @Test
    fun `scaledDisplayText for all_of_section ref ignores scaleFactor`() {
        val sponge = Ingredient(
            name = "опара", amount = 0.0, unit = "г", category = "ref",
            refType = "all_of_section", refSection = "sponge",
        )
        assertThat(RecipeScaler.scaledDisplayText(sponge, scaleFactor = 5.0))
            .isEqualTo("опара (вес рассчитается автоматически)")
    }

    @Test
    fun `scaledDisplayText for portion_of_section ref is NOT scaled — falls through to raw displayText`() {
        // Задокументировано поведение as-is (портировано без изменений из v3):
        // ветка refType != null, но != "all_of_section" возвращает displayText()
        // с исходным (нескейлированным) amount — scaleFactor здесь не применяется.
        val portion = Ingredient(
            name = "часть опары", amount = 150.0, unit = "г", category = "ref",
            refType = "portion_of_section", refSection = "sponge",
        )
        assertThat(RecipeScaler.scaledDisplayText(portion, scaleFactor = 3.0))
            .isEqualTo(portion.displayText())
    }

    @Test
    fun `scaledDisplayText rounds egg count to nearest half and drops trailing zero`() {
        // 1 яйцо (50г), scaleFactor 1.5 -> 1.5 яйца, но formatCount не должен писать "1.5.0"
        val egg = Ingredient(name = "яйцо", amount = 1.0, unit = "шт", category = "egg", eggGrams = 50)
        assertThat(RecipeScaler.scaledDisplayText(egg, scaleFactor = 1.5)).isEqualTo("1.5 шт яйцо")
    }

    @Test
    fun `scaledDisplayText rounds egg count to whole number without decimal`() {
        val egg = Ingredient(name = "яйцо", amount = 2.0, unit = "шт", category = "egg", eggGrams = 50)
        assertThat(RecipeScaler.scaledDisplayText(egg, scaleFactor = 1.0)).isEqualTo("2 шт яйцо")
    }

    @Test
    fun `scaledDisplayText rounds a plain ingredient amount to the nearest gram`() {
        val water = Ingredient(name = "воды", amount = 285.0, unit = "г", category = "water")
        // 285 * 1.333... = 379.999... -> round -> 380
        assertThat(RecipeScaler.scaledDisplayText(water, scaleFactor = 4.0 / 3.0)).isEqualTo("380 г воды")
    }
}
