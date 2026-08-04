package com.polinalinen.madre.model

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.data.repository.RecipeRepository
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

/**
 * Выход и заходы в духовку — на НАСТОЯЩИХ рецептах книги, а не на подобранных
 * числах.
 *
 * Синтетический рецепт не поймал бы ни одну из двух ошибок, ради которых этот
 * файл заведён: у пиццы опара уходит в тесто частью (100 г из 115), а у
 * синнабонов рядом с тестом стоят начинка и крем — и то и другое попадало в
 * «выход теста», из-за чего книга обещала два захода в духовку там, где заход
 * один.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class RealRecipeScaleTest {

    /** Книга читается из тех же assets, что и в приложении. */
    private val book by lazy { RecipeRepository(ApplicationProvider.getApplicationContext()) }

    private fun recipe(id: String): Recipe = runBlocking { book.getRecipe(id) }
        ?: error("в recipes.json нет главы «$id»")

    /** Как считалось до правки: все строки всех секций подряд. */
    private fun naiveSum(recipe: Recipe, portions: Int): Int =
        recipe.ingredients.values.flatten()
            .filter { it.scalable && it.refType == null }
            .sumOf { (it.gramsValue() * RecipeScale.factor(portions)).roundToInt() }

    // --- Пицца: опара уходит в тесто частью ---

    @Test
    fun `pizza dough is the part of the sponge that goes in, plus the dough itself`() {
        val pizza = recipe("pizza")
        // Опары выходит 115 г (15 стартера + 50 воды + 50 муки), в тесто идёт
        // 100 г; тесто — 150 воды + 250 муки + 3 соли + 8 масла = 411 г.
        assertThat(RecipeScale.yieldGrams(pizza, 1)).isEqualTo(511)
    }

    @Test
    fun `the leftover sponge of a pizza never reaches the oven`() {
        val pizza = recipe("pizza")
        // Прежний счёт складывал всю опару целиком — на 15 г больше правды.
        assertThat(naiveSum(pizza, 1)).isEqualTo(526)
        assertThat(RecipeScale.yieldGrams(pizza, 1)).isLessThan(naiveSum(pizza, 1))
    }

    @Test
    fun `pizza scales its dough, portion of the sponge included`() {
        val pizza = recipe("pizza")
        assertThat(RecipeScale.yieldGrams(pizza, 3)).isEqualTo(1533)
        assertThat(RecipeScale.batches(pizza, 1)).isEqualTo(1)
        assertThat(RecipeScale.batches(pizza, 3)).isEqualTo(2)
    }

    // --- Синнабоны: две опары, начинка и крем ---

    @Test
    fun `cinnamon buns weigh their dough, not their filling and cream`() {
        val buns = recipe("cinnamon_buns")
        // Опара 1 (251) + опара 2 (500) + тесто (630, включая 2 яйца по 100 г).
        assertThat(RecipeScale.yieldGrams(buns, 1)).isEqualTo(1381)
        // Начинка (180) и крем (200) в прежнем счёте становились тестом.
        assertThat(naiveSum(buns, 1)).isEqualTo(1761)
    }

    /**
     * Ровно то, что человек читал на странице: «пеките в 2 захода» на одной
     * порции синнабонов — при том что теста там на один заход.
     */
    @Test
    fun `one batch of cinnamon buns fits one oven, and the book stops saying otherwise`() {
        val buns = recipe("cinnamon_buns")
        assertThat(RecipeScale.batches(buns, 1)).isEqualTo(1)
        assertThat(RecipeScale.capacityNote(buns, 1)).isNull()
        // Две порции в одну духовку правда не входят — тут книга говорит вслух.
        assertThat(RecipeScale.yieldGrams(buns, 2)).isEqualTo(2761)
        assertThat(RecipeScale.batches(buns, 2)).isEqualTo(2)
        assertThat(RecipeScale.capacityNote(buns, 2)).contains("2761")
    }

    @Test
    fun `both sponges of the cinnamon buns are counted, and each only once`() {
        val buns = recipe("cinnamon_buns")
        val sponge1 = 38 + 100 + 113
        val sponge2 = 250 + 250
        val dough = 200 + 70 + 50 + 10 + 300
        assertThat(RecipeScale.yieldGrams(buns, 1)).isEqualTo(sponge1 + sponge2 + dough)
    }

    // --- Хлеб: опара целиком, и только один раз ---

    @Test
    fun `home bread folds its whole sponge in exactly once`() {
        val bread = recipe("home_bread")
        // Опара 220 г уходит в тесто целиком: 220 + 875.
        assertThat(RecipeScale.yieldGrams(bread, 1)).isEqualTo(1095)
        // Здесь ссылка «вся секция», и старый счёт совпадал с новым — опара в
        // тесте стоит строкой с нулевым весом.
        assertThat(naiveSum(bread, 1)).isEqualTo(1095)
    }

    // --- текст шага: пересчитывается объявленный вес и только он ---

    @Test
    fun `a step text scales the weights the recipe declared`() {
        val pizza = recipe("pizza")
        val weights = RecipeScaler.scalableWeights(pizza)
        val knead = pizza.timeline.first { it.title == "Замес" }
        // «150 мл воды» — та же строка, что «150 г воды» в списке.
        assertThat(RecipeScaler.scaledStepText(knead.description, 2.0, weights)).contains("300 мл")
        // Минуты остаются минутами.
        assertThat(RecipeScaler.scaledStepText(knead.description, 2.0, weights)).contains("30 мин")
    }

    /**
     * Ложка — единица, которой в рецепте нет числом. Книга её не переписывает и
     * не притворяется, что понимает: молча утроенная «1 ст» была бы выдумкой.
     */
    @Test
    fun `a spoonful in the prose is left exactly as Polina wrote it`() {
        val focaccia = recipe("focaccia")
        val weights = RecipeScaler.scalableWeights(focaccia)
        val sponge = focaccia.timeline.first { it.description.contains(" ст") }
        val scaled = RecipeScaler.scaledStepText(sponge.description, 3.0, weights)
        assertThat(scaled).contains("1 ст")
        // При этом объявленные 120 мл воды пересчитаны.
        assertThat(scaled).contains("360 мл")
    }

    @Test
    fun `every recipe in the book claims some dough and at least one batch`() {
        val ids = listOf(
            "home_bread", "family_bread", "pirozhki", "belyashi", "ciabatta",
            "focaccia", "pizza", "waffles", "pancakes", "cinnamon_buns", "garlic_buns",
        )
        ids.forEach { id ->
            val r = recipe(id)
            assertThat(RecipeScale.yieldGrams(r, 1)).isGreaterThan(0)
            assertThat(RecipeScale.batches(r, 1)).isAtLeast(1)
            // И ни один рецепт не обещает больше теста, чем весит вся его
            // страница целиком: выход — часть рецепта, а не сверх него.
            assertThat(RecipeScale.yieldGrams(r, 1)).isAtMost(naiveSum(r, 1))
        }
    }
}
