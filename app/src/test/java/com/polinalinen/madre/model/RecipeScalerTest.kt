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

    /**
     * Cycle 14. До этого цикла ветка «ref_type есть, но это не all_of_section»
     * возвращала исходные граммы — то есть при ×3 в списке стояло «150 г опары»
     * рядом с втрое выросшим тестом. Ровно тот случай, ради которого фича и
     * заводилась: ни один ингредиент не остаётся со старым масштабом.
     */
    @Test
    fun `scaledDisplayText scales a portion_of_section ref like any other weight`() {
        val portion = Ingredient(
            name = "часть опары", amount = 150.0, unit = "г", category = "ref",
            refType = "portion_of_section", refSection = "sponge",
        )
        assertThat(RecipeScaler.scaledDisplayText(portion, scaleFactor = 3.0))
            .isEqualTo("450 г часть опары")
    }

    @Test
    fun `an all_of_section ref still weighs itself, at any scale`() {
        val sponge = Ingredient(
            name = "опара", amount = 0.0, unit = "г", category = "ref",
            refType = "all_of_section", refSection = "sponge",
        )
        assertThat(RecipeScaler.scaledDisplayText(sponge, scaleFactor = 4.0))
            .isEqualTo("опара (вес рассчитается автоматически)")
    }

    /**
     * Округление до нуля стирало бы ингредиент со страницы: «0 г соли» — это
     * не рецепт. Всё, что весит хоть сколько-то, весит минимум грамм.
     */
    @Test
    fun `a tiny weight never rounds away to zero`() {
        val yeast = Ingredient(name = "сухих дрожжей", amount = 0.4, unit = "г", category = "leavening")
        assertThat(RecipeScaler.scaledDisplayText(yeast, scaleFactor = 1.0)).isEqualTo("1 г сухих дрожжей")
    }

    @Test
    fun `an ingredient with no weight at all stays weightless`() {
        val taste = Ingredient(name = "соль по вкусу", amount = 0.0, unit = "по вкусу", category = "salt")
        assertThat(RecipeScaler.scaledDisplayText(taste, scaleFactor = 3.0)).isEqualTo("соль по вкусу")
    }

    @Test
    fun `a half egg never rounds away either`() {
        val egg = Ingredient(name = "яйцо", amount = 0.2, unit = "шт", category = "egg", eggGrams = 50)
        assertThat(RecipeScaler.scaledDisplayText(egg, scaleFactor = 1.0)).isEqualTo("0.5 шт яйцо")
    }

    /**
     * Cycle 14: текст шага — такая же часть рецепта, как список ингредиентов.
     * «Смешать всю опару с 300 г воды» при ×3 обязано стать 900 г, иначе на
     * одной странице стоят два разных рецепта.
     *
     * Пересчитываются ТОЛЬКО те числа, которые рецепт задал строкой ингредиента
     * ([RecipeScaler.scalableWeights]). Остальное — проза, и разбирать её книга
     * не берётся.
     */
    private val stepRecipe = recipeOf(
        water(300.0),
        flour("мука", 515.0),
        Ingredient(name = "закваски", amount = 37.5, unit = "г", category = "starter"),
        Ingredient(name = "молока", amount = 120.0, unit = "г", category = "milk"),
    )

    private val stepWeights = RecipeScaler.scalableWeights(stepRecipe)

    @Test
    fun `scalableWeights are the weights the recipe itself declares`() {
        assertThat(stepWeights).containsExactly(300.0, 515.0, 37.5, 120.0)
    }

    @Test
    fun `scalableWeights leave out eggs, tastes and weightless lines`() {
        val recipe = recipeOf(
            water(200.0),
            Ingredient(name = "яйцо", amount = 2.0, unit = "шт", category = "egg", eggGrams = 50),
            Ingredient(name = "ваниль", amount = 0.0, unit = "по вкусу", category = "flavor", scalable = false),
            Ingredient(name = "опара", amount = 0.0, unit = "г", category = "ref", refType = "all_of_section"),
        )
        assertThat(RecipeScaler.scalableWeights(recipe)).containsExactly(200.0)
    }

    @Test
    fun `scaledStepText scales grams inside the step text`() {
        assertThat(
            RecipeScaler.scaledStepText("Смешать всю опару с 300 г воды и 515 г муки.", 3.0, stepWeights)
        ).isEqualTo("Смешать всю опару с 900 г воды и 1545 г муки.")
    }

    @Test
    fun `scaledStepText scales millilitres too`() {
        // 120 г молока в списке — «120 мл» в тексте шага: та же строка рецепта.
        assertThat(RecipeScaler.scaledStepText("Добавить 120 мл молока.", 2.0, stepWeights))
            .isEqualTo("Добавить 240 мл молока.")
    }

    @Test
    fun `scaledStepText rounds fractional grams to whole ones`() {
        assertThat(RecipeScaler.scaledStepText("Смешать 37.5 г закваски.", 3.0, stepWeights))
            .isEqualTo("Смешать 113 г закваски.")
    }

    /** Времена, температуры и номера опар — не граммы и не трогаются. */
    @Test
    fun `scaledStepText leaves minutes, degrees and plain numbers alone`() {
        val text = "Опара 2: оставить на 30 минут, печь при 250°C 20 мин."
        assertThat(RecipeScaler.scaledStepText(text, 4.0, stepWeights)).isEqualTo(text)
    }

    @Test
    fun `scaledStepText does not mistake a word for a unit`() {
        // «300 граммов» — слово, а не единица: обрезать его до «г» книга не станет.
        val text = "Отвесить 300 граммов и 2 горсти муки."
        assertThat(RecipeScaler.scaledStepText(text, 2.0, stepWeights)).isEqualTo(text)
    }

    /**
     * Главное в этой правке: число в граммах, которого нет ни в одной строке
     * рецепта, книга не трогает. «Оставьте 100 г опары на следующий раз» —
     * это не порция теста, и утроить его значило бы выдумать рецепт.
     */
    @Test
    fun `a weight the recipe never declared stays exactly as written`() {
        val text = "Оставьте 100 г опары на следующий раз."
        assertThat(RecipeScaler.scaledStepText(text, 3.0, stepWeights)).isEqualTo(text)
    }

    /** Единицы, которых книга не знает, она и не переписывает. */
    @Test
    fun `unfamiliar units are left to the prose they live in`() {
        val text = "Влить 0,5 л воды, добавить 1 ст. ложку соли, раскатать в 2 см толщиной."
        assertThat(RecipeScaler.scaledStepText(text, 3.0, stepWeights)).isEqualTo(text)
    }

    @Test
    fun `a step of a recipe with nothing weighable is left alone entirely`() {
        val text = "Смешать 300 г воды и 515 г муки."
        assertThat(RecipeScaler.scaledStepText(text, 3.0, emptySet())).isEqualTo(text)
    }

    @Test
    fun `scaledStepText at scale one changes nothing`() {
        val text = "Смешать всю опару с 300 г воды и 515 г муки."
        assertThat(RecipeScaler.scaledStepText(text, 1.0, stepWeights)).isEqualTo(text)
    }

    @Test
    fun `scaledStepText survives an empty step`() {
        assertThat(RecipeScaler.scaledStepText("", 3.0, stepWeights)).isEmpty()
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
