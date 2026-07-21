package com.polinalinen.madre.utils

import android.content.Context

/**
 * recipe_id → drawable res id. Заменяет RecipeIcons.kt из v3.
 * Zero emoji: поле recipe.emoji из recipes.json в v4 не рендерится.
 */
fun heroResFor(context: Context, recipeId: String): Int? {
    val id = context.resources.getIdentifier("hero_$recipeId", "drawable", context.packageName)
    return if (id != 0) id else null
}

fun iconResFor(context: Context, recipeId: String): Int? {
    val id = context.resources.getIdentifier("ic_recipe_$recipeId", "drawable", context.packageName)
    return if (id != 0) id else null
}
