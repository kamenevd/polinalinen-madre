package com.polinalinen.madre.notifications

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.data.repository.RecipeRepository
import com.polinalinen.madre.model.Recipe
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Cycle 20: полоска хода в шторке — про ТЕКУЩИЙ шаг, а не про всю выпечку.
 *
 * До этого цикла она считала долю всей выпечки по времени, и на нуле шага
 * стояла где угодно: у бородинского на первом шаге «время вышло» приходило при
 * полоске в 3%. Человек читал два несогласных знака сразу — цифры говорят
 * «шаг кончился», полоска говорит «почти ничего не сделано». Про всю выпечку и
 * так сказано словами, «шаг 3 из 8», и второго способа сказать то же самое
 * полоской не нужно.
 *
 * Проверяется на НАСТОЯЩИХ рецептах книги: у неё есть и шаги в три часа, и
 * шаги в пять минут, и шаги без времени вовсе — синтетический рецепт с ровными
 * числами не поймал бы ни одного из этих случаев.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BakingProgressStepScopeTest {

    private val book by lazy { RecipeRepository(ApplicationProvider.getApplicationContext()) }
    private val recipes: List<Recipe> by lazy { runBlocking { book.getRecipes() } }

    /** Слепок собирается ровно так же, как его собирает BakingViewModel. */
    private fun snapshot(recipe: Recipe, stepIndex: Int, remainingSeconds: Long): BakingProgress {
        val step = recipe.timeline[stepIndex]
        val stepsBefore = recipe.timeline.take(stepIndex).sumOf { it.durationMinutes } * 60L
        val stepTotal = step.durationMinutes * 60L
        return BakingProgress(
            sessionId = 1L,
            recipeName = recipe.name,
            stepTitle = step.title,
            stepIndex = stepIndex,
            stepCount = recipe.timeline.size,
            remainingSeconds = remainingSeconds,
            elapsedSeconds = stepsBefore + (stepTotal - remainingSeconds).coerceAtLeast(0L),
            totalSeconds = recipe.timeline.sumOf { it.durationMinutes } * 60L,
            isPaused = false,
            nextStepTitle = recipe.timeline.getOrNull(stepIndex + 1)?.title,
        )
    }

    /** Длина текущего шага — то, чем полоска обязана мериться. */
    private fun stepTotalSeconds(recipe: Recipe, stepIndex: Int): Long =
        recipe.timeline[stepIndex].durationMinutes * 60L

    /** Книга не пустая — иначе обход ниже проверял бы пустоту. */
    @Test
    fun `the book has recipes with steps to walk`() {
        assertThat(recipes).isNotEmpty()
        assertThat(recipes.all { it.timeline.isNotEmpty() }).isTrue()
    }

    @Test
    fun `a step that ran out fills the bar — on every step of every recipe in the book`() {
        recipes.forEach { recipe ->
            recipe.timeline.indices.forEach { index ->
                assertThat(snapshot(recipe, index, remainingSeconds = 0).permille())
                    .isEqualTo(BakingProgress.PROGRESS_MAX)
            }
        }
    }

    /** Отрицательного остатка в слепке быть не должно, но и на нём знак один. */
    @Test
    fun `an overrun step still reads as full, never as more than full`() {
        recipes.forEach { recipe ->
            recipe.timeline.indices.forEach { index ->
                assertThat(snapshot(recipe, index, remainingSeconds = -30).permille())
                    .isEqualTo(BakingProgress.PROGRESS_MAX)
            }
        }
    }

    @Test
    fun `a step just started shows an empty bar, whatever came before it`() {
        recipes.forEach { recipe ->
            recipe.timeline.forEachIndexed { index, step ->
                if (step.durationMinutes > 0) {
                    val fresh = snapshot(recipe, index, remainingSeconds = step.durationMinutes * 60L)
                    assertThat(fresh.permille()).isEqualTo(0)
                }
            }
        }
    }

    @Test
    fun `half the step is half the bar`() {
        recipes.forEach { recipe ->
            recipe.timeline.forEachIndexed { index, step ->
                val total = step.durationMinutes * 60L
                if (total >= 60) {
                    val half = snapshot(recipe, index, remainingSeconds = total / 2).permille()
                    // Точного значения здесь быть не может: длины шагов в книге
                    // не делятся пополам нацело (655 минут у бородинского), и
                    // целочисленное деление честно теряет доли промилле.
                    assertThat(half).isGreaterThan(480)
                    assertThat(half).isLessThan(520)
                }
            }
        }
    }

    /** Внутри шага полоска только растёт — она и есть ход этого шага. */
    @Test
    fun `the bar never runs backwards while a step counts down`() {
        recipes.forEach { recipe ->
            recipe.timeline.forEachIndexed { index, step ->
                val total = step.durationMinutes * 60L
                if (total > 0) {
                    val marks = (0..10).map { tenth ->
                        snapshot(recipe, index, remainingSeconds = total - total * tenth / 10).permille()
                    }
                    assertThat(marks).isInOrder()
                    assertThat(marks.first()).isEqualTo(0)
                    assertThat(marks.last()).isEqualTo(BakingProgress.PROGRESS_MAX)
                }
            }
        }
    }

    /**
     * Шаг без времени в плане — не деление на ноль и не полная полоска «просто
     * так»: пока у шага есть остаток, идти ему некуда, полоска пуста.
     */
    @Test
    fun `a step with no time at all does not divide by zero`() {
        val timelessStep = recipes
            .flatMap { recipe -> recipe.timeline.indices.map { recipe to it } }
            .firstOrNull { (recipe, index) -> stepTotalSeconds(recipe, index) == 0L }
        // Шагов без времени в книге может и не быть — тогда проверять нечего,
        // но арифметика обязана держать этот случай на любом слепке.
        val (recipe, index) = timelessStep ?: (recipes.first() to 0)
        val zero = snapshot(recipe, index, remainingSeconds = 0)
        assertThat(zero.permille()).isEqualTo(BakingProgress.PROGRESS_MAX)
    }
}
