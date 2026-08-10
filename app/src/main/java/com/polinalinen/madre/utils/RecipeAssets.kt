package com.polinalinen.madre.utils

import androidx.annotation.DrawableRes
import com.polinalinen.madre.R

/**
 * recipe_id → drawable. Только явные `R.drawable.*` — иначе `isShrinkResources`
 * в release выбрасывает hero_*.webp: `getIdentifier` для shrinker невидим
 * (Cycle 19: release APK 2.0 MB без фотокарточек).
 */
@DrawableRes
fun heroResFor(recipeId: String): Int? = when (recipeId) {
    "home_bread" -> R.drawable.hero_home_bread
    "family_bread" -> R.drawable.hero_family_bread
    "pirozhki" -> R.drawable.hero_pirozhki
    "belyashi" -> R.drawable.hero_belyashi
    "ciabatta" -> R.drawable.hero_ciabatta
    "focaccia" -> R.drawable.hero_focaccia
    "pizza" -> R.drawable.hero_pizza
    "waffles" -> R.drawable.hero_waffles
    "pancakes" -> R.drawable.hero_pancakes
    "cinnamon_buns" -> R.drawable.hero_cinnamon_buns
    "garlic_buns" -> R.drawable.hero_garlic_buns
    else -> null
}

/** @deprecated Используй [heroResFor] без Context. */
@Deprecated("Use heroResFor(recipeId)", ReplaceWith("heroResFor(recipeId)"))
fun heroResFor(context: android.content.Context, recipeId: String): Int? = heroResFor(recipeId)

/**
 * Ключ секции ingredients из recipes.json → русский заголовок на развороте.
 * `else` больше не отдаёт сырой english key (dough/filling/cream…).
 */
fun ingredientSectionTitle(sectionKey: String): String = when (sectionKey) {
    "sponge" -> "Опара"
    "sponge1" -> "Опара 1"
    "sponge2" -> "Опара 2"
    "main", "dough" -> "Тесто"
    "filling" -> "Начинка"
    "cream" -> "Крем"
    "topping" -> "Посыпка"
    "glaze" -> "Глазурь"
    else -> sectionKey.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
}
