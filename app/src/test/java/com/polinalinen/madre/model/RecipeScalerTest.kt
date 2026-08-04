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
     * Пересчитывается ТОЛЬКО то, что сам рецепт пометил весом и назвал по имени
     * строки: `{{300 г|main:вода}}`. Остальное — проза, и разбирать её книга не
     * берётся.
     */
    private val stepRecipe = recipeOf(
        water(300.0),
        flour("мука", 515.0),
        Ingredient(name = "закваски", amount = 37.5, unit = "г", category = "starter"),
        Ingredient(name = "молока", amount = 120.0, unit = "г", category = "milk"),
    )

    private val stepBindings = RecipeScaler.scalableBindings(stepRecipe)

    private fun ref(name: String, section: String = "main") = IngredientRef(section, name)

    @Test
    fun `scalableBindings name every weighable line by its section and name`() {
        assertThat(stepBindings.keys).containsExactly(
            ref("вода"), ref("мука"), ref("закваски"), ref("молока"),
        )
        assertThat(stepBindings.getValue(ref("закваски")).amount).isEqualTo(37.5)
    }

    @Test
    fun `scalableBindings leave out eggs, tastes and weightless lines`() {
        val recipe = recipeOf(
            water(200.0),
            Ingredient(name = "яйцо", amount = 2.0, unit = "шт", category = "egg", eggGrams = 50),
            Ingredient(name = "ваниль", amount = 0.0, unit = "по вкусу", category = "flavor", scalable = false),
            Ingredient(name = "опара", amount = 0.0, unit = "г", category = "ref", refType = "all_of_section"),
        )
        assertThat(RecipeScaler.scalableBindings(recipe).keys).containsExactly(ref("вода"))
    }

    /** Одно имя в двух секциях — две разные строки и два разных веса. */
    @Test
    fun `the same name in two sections stays two different lines`() {
        val recipe = Recipe(
            id = "t", name = "t", emoji = "", description = "",
            ingredients = mapOf(
                "sponge" to listOf(flour("муки", 100.0)),
                "main" to listOf(flour("муки", 300.0)),
            ),
            timeline = emptyList(),
        )
        val bindings = RecipeScaler.scalableBindings(recipe)
        assertThat(bindings.getValue(ref("муки", "sponge")).amount).isEqualTo(100.0)
        assertThat(bindings.getValue(ref("муки", "main")).amount).isEqualTo(300.0)
        assertThat(
            RecipeScaler.scaledStepText("Смешать {{100 г|sponge:муки}} муки опары.", 2.0, bindings)
        ).isEqualTo("Смешать 200 г муки опары.")
    }

    @Test
    fun `scaledStepText scales the weights the recipe marked`() {
        assertThat(
            RecipeScaler.scaledStepText(
                "Смешать всю опару с {{300 г|main:вода}} воды и {{515 г|main:мука}} муки.",
                3.0,
                stepBindings,
            )
        ).isEqualTo("Смешать всю опару с 900 г воды и 1545 г муки.")
    }

    @Test
    fun `a marked weight may name millilitres for a line the recipe weighs in grams`() {
        // 120 г молока в списке — «120 мл» в тексте шага: та же строка рецепта,
        // и рецепт говорит об этом прямо, а не совпадением числа.
        assertThat(
            RecipeScaler.scaledStepText("Добавить {{120 мл|main:молока}} молока.", 2.0, stepBindings)
        ).isEqualTo("Добавить 240 мл молока.")
    }

    /** Часть строки — тоже вес этой строки: в чиабатту вода уходит в два приёма. */
    @Test
    fun `a marked weight may be a part of the line it names`() {
        assertThat(
            RecipeScaler.scaledStepText("Влить {{200 г|main:вода}} воды.", 2.0, stepBindings)
        ).isEqualTo("Влить 400 г воды.")
    }

    @Test
    fun `scaledStepText rounds fractional grams to whole ones`() {
        assertThat(
            RecipeScaler.scaledStepText("Смешать {{37.5 г|main:закваски}} закваски.", 3.0, stepBindings)
        ).isEqualTo("Смешать 113 г закваски.")
    }

    /** Времена, температуры и номера опар — не граммы и не трогаются. */
    @Test
    fun `scaledStepText leaves minutes, degrees and plain numbers alone`() {
        val text = "Опара 2: оставить на 30 минут, печь при 250°C 20 мин."
        assertThat(RecipeScaler.scaledStepText(text, 4.0, stepBindings)).isEqualTo(text)
    }

    /**
     * ГЛАВНОЕ В ЭТОЙ ПРАВКЕ. У рецепта есть строка ровно на 100 г — и есть шаг,
     * где те же 100 г названы как то, что НАДО ОСТАВИТЬ. Прежняя книга искала
     * число: раз какой-то ингредиент весит 100, значит и здесь вес, значит ×3.
     * «Оставьте 300 г опары» — рецепт, которого Полина не писала. Совпадение
     * числа доказательством не было и не стало.
     */
    @Test
    fun `a fixed instruction keeps its number even when an ingredient weighs the same`() {
        val recipe = recipeOf(
            flour("муки", 100.0),
            water(200.0),
            sectionName = "sponge",
        )
        val bindings = RecipeScaler.scalableBindings(recipe)
        assertThat(bindings.getValue(ref("муки", "sponge")).amount).isEqualTo(100.0)

        val text = "Смешать {{100 г|sponge:муки}} муки с водой. Оставьте 100 г опары на следующий раз."
        assertThat(RecipeScaler.scaledStepText(text, 3.0, bindings))
            .isEqualTo("Смешать 300 г муки с водой. Оставьте 100 г опары на следующий раз.")
    }

    /** То же самое через единицу: 100 мл в прозе — не 100 г из списка. */
    @Test
    fun `a cross-unit number in the prose is not a weight either`() {
        val text = "Развести в 120 мл тёплой воды. Оставить 300 г теста на закваску."
        assertThat(RecipeScaler.scaledStepText(text, 3.0, stepBindings)).isEqualTo(text)
    }

    @Test
    fun `scaledStepText does not mistake a word for a unit`() {
        val text = "Отвесить 300 граммов и 2 горсти муки."
        assertThat(RecipeScaler.scaledStepText(text, 2.0, stepBindings)).isEqualTo(text)
    }

    /** Единицы, которых книга не знает, она и не переписывает. */
    @Test
    fun `unfamiliar units are left to the prose they live in`() {
        val text = "Влить 0,5 л воды, добавить 1 ст. ложку соли, раскатать в 2 см толщиной."
        assertThat(RecipeScaler.scaledStepText(text, 3.0, stepBindings)).isEqualTo(text)
    }

    /**
     * Пометка на строку, которой в рецепте нет, ничего не пересчитывает — и
     * фигурных скобок человеку не показывает. Молчание здесь честнее выдумки,
     * а поймать такую пометку — дело теста на всю книгу.
     */
    @Test
    fun `a mark naming a line the recipe does not have is printed as written`() {
        val text = "Добавить {{300 г|main:сахара}} сахара."
        assertThat(RecipeScaler.scaledStepText(text, 3.0, stepBindings))
            .isEqualTo("Добавить 300 г сахара.")
    }

    @Test
    fun `a mark naming an ingredient counted in pieces is printed as written`() {
        val recipe = recipeOf(
            water(200.0),
            Ingredient(name = "яйцо", amount = 2.0, unit = "шт", category = "egg", eggGrams = 50),
        )
        val text = "Вбить {{2 г|main:яйцо}} яйца."
        assertThat(RecipeScaler.scaledStepText(text, 3.0, RecipeScaler.scalableBindings(recipe)))
            .isEqualTo("Вбить 2 г яйца.")
    }

    @Test
    fun `a mark the book cannot read is printed as written`() {
        val text = "Влить {{полстакана|main:вода}} воды."
        assertThat(RecipeScaler.scaledStepText(text, 3.0, stepBindings))
            .isEqualTo("Влить полстакана воды.")
    }

    @Test
    fun `stepQuantities reads every mark the step carries`() {
        val text = "Смешать {{300 г|main:вода}} воды и {{515 г|main:мука}} муки."
        assertThat(RecipeScaler.stepQuantityCount(text)).isEqualTo(2)
        assertThat(RecipeScaler.stepQuantities(text)).containsExactly(
            StepQuantity(300.0, "г", ref("вода")),
            StepQuantity(515.0, "г", ref("мука")),
        )
    }

    @Test
    fun `a step of a recipe with nothing weighable is left alone entirely`() {
        val text = "Смешать {{300 г|main:вода}} воды и {{515 г|main:мука}} муки."
        assertThat(RecipeScaler.scaledStepText(text, 3.0, emptyMap()))
            .isEqualTo("Смешать 300 г воды и 515 г муки.")
    }

    @Test
    fun `scaledStepText at scale one changes nothing but the marks`() {
        val text = "Смешать всю опару с {{300 г|main:вода}} воды и {{515 г|main:мука}} муки."
        assertThat(RecipeScaler.scaledStepText(text, 1.0, stepBindings))
            .isEqualTo("Смешать всю опару с 300 г воды и 515 г муки.")
    }

    @Test
    fun `scaledStepText survives an empty step`() {
        assertThat(RecipeScaler.scaledStepText("", 3.0, stepBindings)).isEmpty()
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
