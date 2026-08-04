package com.polinalinen.madre.model

import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Cycle 14: «на сколько печём» — единственный источник масштаба рецепта.
 *
 * [RecipeScaler] умеет считать граммы; здесь живёт всё остальное, что человек
 * должен узнать про выбранное количество порций, и живёт в одном месте:
 * коэффициент, выход теста, влезает ли это в одну духовку и что порции
 * НЕ меняют времена этапов.
 *
 * Последнее — не украшение. Тесто на пять семей зреет ровно столько же,
 * сколько на одну, и книга говорит об этом вслух: молчание здесь читалось бы
 * как «время тоже пересчиталось».
 */
object RecipeScale {

    const val MIN_PORTIONS = 1
    const val MAX_PORTIONS = 5

    /**
     * Сколько теста домашняя духовка берёт за один заход. Число не из рецепта —
     * рецепты объёма посуды не задают вовсе, — а из кухни: противень бытовой
     * духовки принимает примерно столько, дальше пекут в два захода.
     */
    const val OVEN_BATCH_GRAMS = 1500

    /** Порции меняют граммы — и только их. */
    const val TIMING_NOTE = "время этапов от количества порций не меняется — тесто зреет столько же"

    fun clampPortions(portions: Int): Int = portions.coerceIn(MIN_PORTIONS, MAX_PORTIONS)

    fun factor(portions: Int): Double = clampPortions(portions).toDouble()

    /** Выход теста в целых граммах при выбранных порциях. */
    fun yieldGrams(recipe: Recipe, portions: Int): Int {
        val scale = factor(portions)
        // Складываем уже округлённые граммы, а не округляем сумму: в списке
        // ингредиентов человек видит именно округлённые числа, и выход обязан
        // сходиться с тем, что он положит на весы.
        return recipe.ingredients.values
            .flatten()
            .filter { it.scalable && it.refType == null }
            .sumOf { (it.gramsValue() * scale).roundToInt() }
    }

    /** Строка выхода, либо null — если весить в рецепте нечего. */
    fun yieldText(recipe: Recipe, portions: Int): String? {
        val grams = yieldGrams(recipe, portions)
        return if (grams <= 0) null else "выход ≈ $grams г теста"
    }

    /** Сколько заходов в духовку — всегда хотя бы один. */
    fun batches(recipe: Recipe, portions: Int): Int {
        val grams = yieldGrams(recipe, portions)
        if (grams <= 0) return 1
        return ceil(grams.toDouble() / OVEN_BATCH_GRAMS).toInt().coerceAtLeast(1)
    }

    /**
     * Ограничение объёма — явно и с настоящими числами, а не «много теста».
     * null — всё влезает за раз, и молчать здесь честно.
     */
    fun capacityNote(recipe: Recipe, portions: Int): String? {
        val batches = batches(recipe, portions)
        if (batches <= 1) return null
        return "теста выйдет ${yieldGrams(recipe, portions)} г — в домашнюю духовку за раз " +
            "входит около $OVEN_BATCH_GRAMS г: пеките в $batches ${batchWord(batches)}"
    }

    /**
     * Время по плану. [portions] в расчёт не входит намеренно — и именно это
     * закреплено тестом: порции не имеют права молча двигать времена этапов.
     */
    @Suppress("UNUSED_PARAMETER")
    fun totalMinutes(recipe: Recipe, portions: Int): Int =
        recipe.timeline.sumOf { it.durationMinutes }

    private fun batchWord(n: Int) = when {
        n % 10 == 1 && n % 100 != 11 -> "заход"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "захода"
        else -> "заходов"
    }
}
