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

        if (ingredient.refType != null) {
            return when (ingredient.refType) {
                "all_of_section" -> "опара (вес рассчитается автоматически)"
                else -> ingredient.displayText()
            }
        }

        if (ingredient.eggGrams != null) {
            val scaled = roundToHalf(ingredient.amount * scaleFactor)
            return "${formatCount(scaled)} ${ingredient.unit} ${ingredient.name}"
        }

        val scaledAmount = round(ingredient.amount * scaleFactor)
        return ingredient.displayText(scaledAmount)
    }

    private fun roundToHalf(value: Double): Double = round(value * 2.0) / 2.0

    private fun formatCount(value: Double): String =
        if (abs(value - value.toLong().toDouble()) < 0.001) {
            value.toLong().toString()
        } else {
            value.toString()
        }
}
