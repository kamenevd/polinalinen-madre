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

    /** Ссылка на секцию целиком: в тесто уходит вся опара, сколько её вышло. */
    const val ALL_OF_SECTION = "all_of_section"

    /**
     * Секции, которые в тесто не замешивают. Начинку сворачивают внутрь, крем
     * кладут сверху уже на остывшее — на объём теста, который надо расстоять и
     * посадить в духовку, они не влияют. До этой правки синнабоны считали
     * начинку и крем тестом и сообщали про два захода там, где заход один.
     */
    private val NON_DOUGH_SECTIONS = setOf(
        "filling", "cream", "topping", "glaze", "frosting", "sauce", "syrup", "decor",
    )

    fun clampPortions(portions: Int): Int = portions.coerceIn(MIN_PORTIONS, MAX_PORTIONS)

    /**
     * Секции, из которых получается тесто, — всё, кроме начинок и кремов.
     *
     * Публично, потому что инвариант «сумма показанного = обещанный выход»
     * (RecipeScaleInvariantTest) обязан складывать РОВНО те же строки, что
     * складывает [yieldGrams]. Свой список секций в тесте означал бы, что
     * тест и книга расходятся молча.
     */
    fun doughSections(recipe: Recipe): Map<String, List<Ingredient>> =
        recipe.ingredients.filterKeys { it.lowercase() !in NON_DOUGH_SECTIONS }

    /**
     * Финальное тесто — секция, которая ссылается на опару. null означает, что
     * ссылок в рецепте нет вовсе: тогда замешивать нечего кроме написанного, и
     * все тестовые секции складываются как есть.
     */
    fun finalDoughSection(recipe: Recipe): List<Ingredient>? =
        doughSections(recipe).values.firstOrNull { items -> items.any { it.refType != null } }

    fun factor(portions: Int): Double = clampPortions(portions).toDouble()

    /**
     * Выход теста в целых граммах — того теста, которое правда поедет в духовку.
     *
     * Считается не «сумма всех строк рецепта», а вес ФИНАЛЬНОЙ секции: её
     * собственные ингредиенты плюс то, что приносят её ссылки. Иначе опара,
     * посчитанная и в своей секции, и в тесте, удваивается, а начинка с кремом
     * записываются в тесто, которого они не составляют.
     *
     * Ссылка читается по своему типу и никак иначе:
     *  · [ALL_OF_SECTION] — вся опара, то есть вес её секции;
     *  · всё остальное («часть секции») — ровно тот вес, который написан в
     *    самой строке. У пиццы опары выходит 115 г, а в тесто идёт 100 —
     *    оставшиеся 15 г в духовку не едут и в выход не входят.
     */
    fun yieldGrams(recipe: Recipe, portions: Int): Int {
        val scale = factor(portions)
        val finalSection = finalDoughSection(recipe)
            ?: return doughSections(recipe).values.flatten().sumOf { ownGrams(it, scale) }

        return finalSection.sumOf { ownGrams(it, scale) } +
            finalSection.filter { it.refType != null }.sumOf { refGrams(recipe, it, scale) }
    }

    /**
     * Собственный вес строки. Ссылка своего веса не имеет — её приносит
     * [refGrams], и складывать её здесь значило бы посчитать опару дважды.
     *
     * Складываем уже округлённые граммы, а не округляем сумму: в списке
     * ингредиентов человек видит именно округлённые числа, и выход обязан
     * сходиться с тем, что он положит на весы.
     */
    private fun ownGrams(ingredient: Ingredient, scale: Double): Int =
        if (!ingredient.scalable || ingredient.refType != null) {
            0
        } else {
            (ingredient.gramsValue() * scale).roundToInt()
        }

    /** Сколько граммов приносит в тесто одна ссылка. */
    private fun refGrams(recipe: Recipe, ref: Ingredient, scale: Double): Int {
        if (!ref.scalable) return 0
        val portionOfSection = { (ref.gramsValue() * scale).roundToInt() }
        if (ref.refType != ALL_OF_SECTION) return portionOfSection()
        // Опары в этой книге плоские: сама на другую опару не ссылается,
        // поэтому её вес — сумма собственных строк её секции. Секции с таким
        // именем в рецепте нет — верим числу в самой строке, а не выдумываем.
        val section = recipe.ingredients[ref.refSection] ?: return portionOfSection()
        return section.sumOf { ownGrams(it, scale) }
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
     * Ограничение духовки — только про заходы и объём противня.
     * Граммы выхода живут в [yieldText]; повторять их здесь — дубль на странице
     * («выход ≈ 2190 г» и тут же «теста выйдет 2190 г»).
     * null — всё влезает за раз, и молчать здесь честно.
     */
    fun capacityNote(recipe: Recipe, portions: Int): String? {
        val batches = batches(recipe, portions)
        if (batches <= 1) return null
        return "в домашнюю духовку за раз входит около $OVEN_BATCH_GRAMS г: " +
            "пеките в $batches ${batchWord(batches)}"
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
