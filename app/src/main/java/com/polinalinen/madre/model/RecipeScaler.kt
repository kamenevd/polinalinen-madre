package com.polinalinen.madre.model

import kotlin.math.abs
import kotlin.math.round

/**
 * Поварской калькулятор: пересчёт рецепта по целевому весу муки или теста.
 * Портировано БЕЗ ИЗМЕНЕНИЙ из v3 (master / feat/living-culture-v2 — идентичные хеши,
 * математика подтверждена рабочей — см. madre-v4-plan.md §1).
 *
 * Baker percentage: каждый ингредиент выражается в % от суммарного веса муки.
 * Ссылочные ингредиенты ([Ingredient.refType]) исключаются из суммарного веса теста,
 * чтобы не считать секцию дважды (она уже учтена отдельной секцией).
 */
object RecipeScaler {

    fun totalFlourGrams(recipe: Recipe): Double =
        recipe.ingredients.values
            .flatten()
            .filter { it.isFlour }
            .sumOf { it.gramsValue() }

    fun totalDoughGrams(recipe: Recipe): Double =
        recipe.ingredients.values
            .flatten()
            .filter { it.scalable && it.refType == null }
            .sumOf { it.gramsValue() }

    fun bakerPercentage(ingredient: Ingredient, recipe: Recipe): Double {
        val total = totalFlourGrams(recipe)
        if (total == 0.0) return 0.0
        return ingredient.gramsValue() / total * 100.0
    }

    fun scaleByFlour(recipe: Recipe, targetFlourGrams: Double): Double {
        val total = totalFlourGrams(recipe)
        if (total == 0.0) return 1.0
        return targetFlourGrams / total
    }

    fun scaleByTotalWeight(recipe: Recipe, targetTotalGrams: Double): Double {
        val total = totalDoughGrams(recipe)
        if (total == 0.0) return 1.0
        return targetTotalGrams / total
    }

    fun scaledDisplayText(ingredient: Ingredient, scaleFactor: Double): String {
        if (!ingredient.scalable) return ingredient.name

        // Ссылка на всю секцию веса не имеет вовсе: он складывается из той
        // секции, а она уже пересчитана — своего числа здесь нет и быть не может.
        if (ingredient.refType == "all_of_section") {
            return "опара (вес рассчитается автоматически)"
        }

        if (ingredient.eggGrams != null) {
            val scaled = roundToHalf(ingredient.amount * scaleFactor)
            return "${formatCount(scaled)} ${ingredient.unit} ${ingredient.name}"
        }

        // Cycle 14: ссылка «часть секции» — обычный вес и масштабируется как
        // обычный вес. До этого цикла она возвращала исходные граммы, и при ×3
        // в списке стояло «150 г опары» рядом с втрое выросшим тестом.
        val scaledAmount = roundWeight(ingredient.amount * scaleFactor)
        return ingredient.displayText(scaledAmount)
    }

    /**
     * Вес, который книга правда знает: граммы каждой строки рецепта, какой она
     * написана при одной порции. Ровно эти числа — и никакие другие — можно
     * пересчитать в тексте шага, потому что только про них известно, что они
     * зависят от количества порций.
     *
     * Яйца сюда не идут: они считаются штуками и половинками, и «2 г» из
     * текста шага не то же самое, что «2 шт».
     */
    fun scalableWeights(recipe: Recipe): Set<Double> =
        recipe.ingredients.values
            .flatten()
            .filter { it.scalable && it.eggGrams == null && it.amount > 0.0 }
            .filter { it.unit == GRAMS || it.unit == MILLILITRES }
            .map { it.amount }
            .toSet()

    /**
     * Cycle 14: текст шага — такая же часть рецепта, как список ингредиентов.
     * «Смешать всю опару с 300 г воды» при ×3 обязано стать 900 г, иначе на
     * одной странице стоят два разных рецепта — и книжный, и таймерный текст
     * рассказывают человеку с весами разное.
     *
     * ЧТО ИМЕННО ПЕРЕСЧИТЫВАЕТСЯ И ПОЧЕМУ ТОЛЬКО ЭТО. Разбирать свободный текст
     * книга не умеет и не притворяется, что умеет: «1 ст», «0,5 л», «на 2 см
     * толщиной», «оставьте 100 г опары на следующий раз» — всё это числа, про
     * которые из прозы не узнать, растут ли они с порциями. Поэтому меняется
     * ровно то, что рецепт уже задал строкой ингредиента ([scalableWeights]):
     * число в граммах или миллилитрах, в точности совпадающее с весом одной из
     * его строк. Любое другое число остаётся как написано — молча переписать
     * его значило бы выдумать рецепт за Полину.
     *
     * Слово, начинающееся с той же буквы («300 граммов»), единицей не считается:
     * за ней должна кончаться кириллица.
     */
    fun scaledStepText(text: String, scaleFactor: Double, scalableWeights: Set<Double>): String =
        WEIGHT_IN_TEXT.replace(text) { match ->
            val amount = match.groupValues[1].replace(',', '.').toDoubleOrNull()
                ?: return@replace match.value
            if (scalableWeights.none { abs(it - amount) < WEIGHT_TOLERANCE }) return@replace match.value
            val unit = match.groupValues[2]
            "${formatCount(roundWeight(amount * scaleFactor))} $unit"
        }

    /**
     * Вес округляется до грамма, но никогда не до нуля: «0 г соли» — это не
     * рецепт, а стёртая со страницы строка. Ноль остаётся нулём только если
     * ингредиент и правда ничего не весит.
     */
    private fun roundWeight(value: Double): Double {
        // Math.round, а не kotlin.math.round: последний округляет половинки к
        // чётному (112.5 → 112), и «37.5 г закваски ×3» разошлось бы между
        // списком ингредиентов и текстом шага. На кухне половинка идёт вверх.
        val rounded = Math.round(value).toDouble()
        return if (rounded == 0.0 && value > 0.0) 1.0 else rounded
    }

    /** Половинки яиц тоже не пропадают: четверть яйца — это половина, а не ноль. */
    private fun roundToHalf(value: Double): Double {
        val rounded = round(value * 2.0) / 2.0
        return if (rounded == 0.0 && value > 0.0) 0.5 else rounded
    }

    /**
     * Число, пробел (необязательный), «г» или «мл» — и дальше НЕ кириллица,
     * иначе это начало слова, а не единица измерения.
     */
    private val WEIGHT_IN_TEXT = Regex("(\\d+(?:[.,]\\d+)?)\\s*(мл|г)(?![а-яёА-ЯЁ])")

    private const val GRAMS = "г"

    /**
     * Воду рецепты этой книги задают в граммах, а в тексте шага та же вода
     * иногда написана миллилитрами («120 мл» при «120 г воды» в списке). Один
     * грамм воды — один миллилитр, и совпадение числа со строкой ингредиента
     * здесь и есть доказательство, что речь о ней.
     */
    private const val MILLILITRES = "мл"

    /** Числа приходят из текста и из JSON — сравнивать их байт в байт нельзя. */
    private const val WEIGHT_TOLERANCE = 0.001

    private fun formatCount(value: Double): String =
        if (abs(value - value.toLong().toDouble()) < 0.001) {
            value.toLong().toString()
        } else {
            value.toString()
        }
}
