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

    private fun starter(grams: Double) =
        Ingredient(name = "закваска", amount = grams, unit = "г", category = "starter")

    private fun sugar(grams: Double) =
        Ingredient(name = "сахар", amount = grams, unit = "г", category = "sugar")

    private fun butter(grams: Double) =
        Ingredient(name = "масло", amount = grams, unit = "г", category = "fat")

    private fun recipeOf(vararg ingredients: Ingredient): Recipe =
        recipeOfSections("main" to ingredients.toList())

    private fun recipeOfSections(vararg sections: Pair<String, List<Ingredient>>): Recipe = Recipe(
        id = "test",
        name = "Test",
        emoji = "",
        description = "",
        ingredients = linkedMapOf(*sections),
        timeline = listOf(TimelineStep(StepType.WAIT, "Расстойка", "", 120)),
    )

    private fun allOfSection(section: String) = Ingredient(
        name = "опара", amount = 0.0, unit = "г", category = "ref",
        refType = "all_of_section", refSection = section,
    )

    private fun portionOfSection(section: String, grams: Double) = Ingredient(
        name = "опары", amount = grams, unit = "г", category = "ref",
        refType = "portion_of_section", refSection = section,
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

    // --- выход = то тесто, которое правда поедет в духовку ---

    /**
     * Опара стоит в рецепте дважды: своей секцией и строкой «вся опара» в
     * тесте. Сложить обе — значит обещать вдвое больше теста, чем выйдет.
     */
    @Test
    fun `a preferment folded into the dough is weighed once, not twice`() {
        val recipe = recipeOfSections(
            "sponge" to listOf(flour(95.0), water(95.0)),
            "main" to listOf(allOfSection("sponge"), flour(500.0), water(300.0)),
        )
        // 190 г опары + 800 г теста, а не 190 + 190 + 800.
        assertThat(RecipeScale.yieldGrams(recipe, 1)).isEqualTo(990)
        assertThat(RecipeScale.yieldGrams(recipe, 2)).isEqualTo(1980)
    }

    /**
     * «Часть секции» — ровно тот вес, который написан. Остаток опары в духовку
     * не едет, и записывать его в выход книга не имеет права.
     */
    @Test
    fun `only the portion of the preferment that goes in counts as dough`() {
        val recipe = recipeOfSections(
            "sponge" to listOf(flour(50.0), water(50.0), starter(15.0)),
            "main" to listOf(portionOfSection("sponge", 100.0), flour(250.0), water(150.0)),
        )
        // Опары вышло 115 г, в тесто ушло 100 — выход 100 + 400.
        assertThat(RecipeScale.yieldGrams(recipe, 1)).isEqualTo(500)
        assertThat(RecipeScale.yieldGrams(recipe, 3)).isEqualTo(1500)
    }

    /** Начинку сворачивают внутрь, крем кладут сверху — тестом они не станут. */
    @Test
    fun `filling and cream are not dough and never were`() {
        val recipe = recipeOfSections(
            "sponge" to listOf(flour(100.0), water(100.0)),
            "dough" to listOf(allOfSection("sponge"), flour(300.0), water(200.0)),
            "filling" to listOf(sugar(100.0), butter(70.0)),
            "cream" to listOf(sugar(50.0)),
        )
        assertThat(RecipeScale.yieldGrams(recipe, 1)).isEqualTo(700)
    }

    /** Две опары — две ссылки, и каждая приносит свою секцию, а не чужую. */
    @Test
    fun `two preferments each bring their own section`() {
        val recipe = recipeOfSections(
            "sponge1" to listOf(flour(100.0), water(113.0)),
            "sponge2" to listOf(flour(250.0), water(250.0)),
            "dough" to listOf(allOfSection("sponge1"), allOfSection("sponge2"), flour(300.0)),
        )
        assertThat(RecipeScale.yieldGrams(recipe, 1)).isEqualTo(213 + 500 + 300)
    }

    /** Ссылка в никуда — не повод молча потерять или выдумать граммы. */
    @Test
    fun `a reference to a section that is not there falls back to its own number`() {
        val recipe = recipeOfSections(
            "main" to listOf(portionOfSection("nowhere", 120.0), flour(300.0)),
        )
        assertThat(RecipeScale.yieldGrams(recipe, 1)).isEqualTo(420)
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
        // Граммы — только в yieldText; capacityNote не дублирует их.
        assertThat(RecipeScale.yieldText(recipe, 3)).isEqualTo("выход ≈ 2550 г теста")
        val note = RecipeScale.capacityNote(recipe, 3)
        assertThat(note).isNotNull()
        assertThat(note).doesNotContain("2550")
        assertThat(note).doesNotContain("выход")
        assertThat(note).contains("${RecipeScale.OVEN_BATCH_GRAMS}")
        assertThat(note).contains("2 захода")
    }

    @Test
    fun `capacity note never repeats the yield grams already on the page`() {
        val recipe = recipeOf(flour(500.0), water(350.0))
        val grams = RecipeScale.yieldGrams(recipe, 2)
        assertThat(grams).isGreaterThan(RecipeScale.OVEN_BATCH_GRAMS)
        val note = RecipeScale.capacityNote(recipe, 2)
        assertThat(note).isNotNull()
        assertThat(note).doesNotContain("$grams")
        assertThat(note).startsWith("в домашнюю духовку")
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
     * Русское склонение на числах, где его чаще всего и ломают: 11 — не «один»,
     * 21 — не «много», 12 и 14 — не «два» и не «четыре».
     */
    @Test
    fun `the batch count is spelled the way Russian actually declines it`() {
        fun noteFor(grams: Double): String =
            RecipeScale.capacityNote(recipeOf(flour(grams)), 1).orEmpty()

        // 11 заходов: 15001..16500 г теста.
        assertThat(noteFor(16_000.0)).contains("в 11 заходов")
        // 12: 16501..18000
        assertThat(noteFor(17_000.0)).contains("в 12 заходов")
        // 14: 19501..21000
        assertThat(noteFor(20_000.0)).contains("в 14 заходов")
        // 21: 30001..31500
        assertThat(noteFor(30_100.0)).contains("в 21 заход")
    }

    @Test
    fun `two, three and five batches are spelled right too`() {
        fun noteFor(grams: Double): String =
            RecipeScale.capacityNote(recipeOf(flour(grams)), 1).orEmpty()

        assertThat(noteFor(2_000.0)).contains("в 2 захода")
        assertThat(noteFor(4_000.0)).contains("в 3 захода")
        assertThat(noteFor(7_000.0)).contains("в 5 заходов")
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
