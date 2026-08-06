package com.polinalinen.madre.model

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.data.repository.RecipeRepository
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * Cycle 15: инвариант масштаба — сумма того, ЧТО ЧЕЛОВЕК ВИДИТ в списке
 * ингредиентов, равна выходу теста, который книга обещает строкой ниже.
 *
 * Обе величины уже считаются в приложении, но разными путями: выход — из
 * [RecipeScale] по модели, список — через [RecipeScaler.scaledDisplayText] по
 * строкам. Пути с разным округлением расходятся тихо: на странице стоят числа,
 * которые не складываются в обещанный выход, и заметить это может только
 * человек с весами.
 *
 * Поэтому граммы здесь берутся ИЗ ТЕКСТА, который уходит на экран, а не из
 * модели: тест, читающий модель обоими способами, проверял бы сам себя.
 *
 * Книга настоящая — все главы recipes.json, все порции 1..5.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RecipeScaleInvariantTest {

    /** Допуск: единственный законный источник расхождения — округление до грамма. */
    private val toleranceGrams = 1.0

    private val book by lazy { RecipeRepository(ApplicationProvider.getApplicationContext()) }

    private val recipes: List<Recipe> by lazy { runBlocking { book.getRecipes() } }

    private val portions = RecipeScale.MIN_PORTIONS..RecipeScale.MAX_PORTIONS

    // --- чтение граммов из показанной строки ---

    /**
     * Сколько граммов человек прочитает в этой строке списка.
     *
     * Разбирается ровно то, что нарисовано: ведущее число и единица.
     * «по вкусу» и прочие строки без числа не весят ничего — [RecipeScale]
     * считает их так же.
     */
    private fun shownGrams(recipe: Recipe, ingredient: Ingredient, portionCount: Int): Double {
        if (!ingredient.scalable) return 0.0
        val scale = RecipeScale.factor(portionCount)

        // Ссылка на всю опару своего числа не показывает вовсе — на экране
        // стоит «вес рассчитается автоматически». Её вес человек читает выше,
        // в секции самой опары, и складывать надо именно его.
        if (ingredient.refType == RecipeScale.ALL_OF_SECTION) {
            val section = recipe.ingredients[ingredient.refSection]
                ?: error("глава «${recipe.id}»: ссылка на секцию «${ingredient.refSection}», которой нет")
            return section.sumOf { shownGrams(recipe, it, portionCount) }
        }

        val text = RecipeScaler.scaledDisplayText(ingredient, scale)
        val amount = leadingAmount(text) ?: return 0.0

        // Яйца показаны штуками, а весят граммами: «2 шт» — это два раза по
        // egg_grams, и на весах человека окажется именно столько.
        val eggGrams = ingredient.eggGrams
        return if (eggGrams != null) amount * eggGrams else amount
    }

    /** Число, с которого начинается строка, либо null — если строка без числа. */
    private fun leadingAmount(text: String): Double? =
        LEADING_NUMBER.find(text)?.groupValues?.get(1)?.replace(',', '.')?.toDoubleOrNull()

    /** Сумма показанного — теми же секциями, которыми считает [RecipeScale.yieldGrams]. */
    private fun shownYield(recipe: Recipe, portionCount: Int): Double {
        val finalSection = RecipeScale.finalDoughSection(recipe)
            ?: return RecipeScale.doughSections(recipe).values.flatten()
                .sumOf { shownGrams(recipe, it, portionCount) }
        return finalSection.sumOf { shownGrams(recipe, it, portionCount) }
    }

    // --- сам инвариант ---

    @Test
    fun `every chapter adds up to the yield it promises`() {
        assertThat(recipes).isNotEmpty()
        val broken = mutableListOf<String>()

        recipes.forEach { recipe ->
            portions.forEach { portionCount ->
                val promised = RecipeScale.yieldGrams(recipe, portionCount).toDouble()
                val shown = shownYield(recipe, portionCount)
                val drift = abs(shown - promised)
                if (drift > toleranceGrams) {
                    broken += "«${recipe.name}» (${recipe.id}) ×$portionCount: " +
                        "в списке ${fmt(shown)} г, обещано ${fmt(promised)} г, расхождение ${fmt(drift)} г"
                }
            }
        }

        assertThat(broken).isEmpty()
    }

    /**
     * Тест обязан уметь падать. Рецепт, где выход не сходится со списком, —
     * ровно то, что этот файл ищет, и на нём проверка должна краснеть.
     */
    @Test
    fun `a recipe whose list does not add up is caught`() {
        val honest = Recipe(
            id = "honest", name = "Честный", emoji = "", description = "",
            ingredients = mapOf(
                "main" to listOf(
                    Ingredient(name = "муки", amount = 300.0, unit = "г", category = "flour", isFlour = true),
                    Ingredient(name = "воды", amount = 200.0, unit = "г", category = "liquid"),
                ),
            ),
            timeline = emptyList(),
        )
        assertThat(abs(shownYield(honest, 1) - RecipeScale.yieldGrams(honest, 1).toDouble()))
            .isAtMost(toleranceGrams)

        // Та же книга, но одна строка показана не тем весом, каким посчитана:
        // «по вкусу» не весит ничего на экране, а в модели стоят 200 г.
        val lying = honest.copy(
            ingredients = mapOf(
                "main" to honest.ingredients.getValue("main").map {
                    if (it.name == "воды") it.copy(unit = "по вкусу") else it
                },
            ),
        )
        assertThat(abs(shownYield(lying, 1) - RecipeScale.yieldGrams(lying, 1).toDouble()))
            .isGreaterThan(toleranceGrams)
    }

    /**
     * Каждая ссылка «вся опара» указывает на секцию, которая в рецепте правда
     * есть. Иначе список показал бы «вес рассчитается автоматически», а выход
     * подставил бы число из самой строки — и они разошлись бы молча.
     */
    @Test
    fun `every reference points at a section the recipe really has`() {
        recipes.forEach { recipe ->
            recipe.ingredients.values.flatten()
                .filter { it.refType == RecipeScale.ALL_OF_SECTION }
                .forEach { ref ->
                    assertThat(recipe.ingredients.keys).contains(ref.refSection)
                }
        }
    }

    /** Выход растёт вместе с порциями — иначе масштаб не масштаб. */
    @Test
    fun `the yield of every chapter grows with the portions`() {
        recipes.forEach { recipe ->
            val yields = portions.map { RecipeScale.yieldGrams(recipe, it) }
            if (yields.first() > 0) {
                assertThat(yields).isInStrictOrder()
            }
        }
    }

    private fun fmt(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else "%.1f".format(value)

    private companion object {
        /** «515 г муки» → 515; «2 шт яйца» → 2; «по вкусу» → ничего. */
        val LEADING_NUMBER = Regex("^(\\d+(?:[.,]\\d+)?)\\s")
    }
}
