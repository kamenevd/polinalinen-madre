package com.polinalinen.madre.ui.razvorot

import com.polinalinen.madre.model.BakingSession
import com.polinalinen.madre.model.Recipe
import java.util.Locale
import java.math.BigDecimal
import java.math.RoundingMode

/** Local UI classification, not a change to the recipe database. */
enum class CatalogFilter(val label: String) {
    ALL("Все"), BREAD("Хлеб"), BUNS("Пироги и булки"), BREAKFAST("К завтраку"), ACTIVE("В печи")
}

data class CatalogMatch(val recipe: Recipe, val chapter: Int, val ingredient: String? = null)

object BookCatalog {
    fun number(value: Double, places: Int = 2): String = BigDecimal.valueOf(value)
        .setScale(places, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString().replace('.', ',')
    private val bread = setOf("home_bread", "family_bread", "ciabatta", "focaccia")
    private val breakfast = setOf("waffles", "pancakes")
    private fun normalized(value: String) = value.lowercase(Locale.forLanguageTag("ru")).replace('ё', 'е')
    fun category(recipe: Recipe) = when (recipe.id) {
        in bread -> CatalogFilter.BREAD
        in breakfast -> CatalogFilter.BREAKFAST
        else -> CatalogFilter.BUNS
    }
    fun search(recipes: List<Recipe>, query: String, filter: CatalogFilter,
        sessions: List<BakingSession> = emptyList()): List<CatalogMatch> {
        val needle = normalized(query.trim())
        val active = sessions.filterNot { it.isCompleted }.map { it.recipe.id }.toSet()
        return recipes.mapIndexedNotNull { index, recipe ->
            if (filter == CatalogFilter.ACTIVE && recipe.id !in active) return@mapIndexedNotNull null
            if (filter !in setOf(CatalogFilter.ALL, CatalogFilter.ACTIVE) && category(recipe) != filter) return@mapIndexedNotNull null
            if (needle.isEmpty() || normalized(recipe.name).contains(needle)) CatalogMatch(recipe, index)
            else recipe.ingredients.values.flatten().firstOrNull { normalized(it.name).contains(needle) }
                ?.let { CatalogMatch(recipe, index, it.name) }
        }
    }
    fun duration(minutes: Int): String = when {
        minutes < 60 -> "$minutes мин"
        minutes % 60 == 0 -> "${minutes / 60} ч"
        minutes % 60 == 30 -> "${minutes / 60},5 ч"
        else -> "${minutes / 60} ч ${minutes % 60} мин"
    }
}
