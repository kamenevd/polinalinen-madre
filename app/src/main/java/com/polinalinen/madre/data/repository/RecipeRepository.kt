package com.polinalinen.madre.data.repository

import android.content.Context
import com.google.gson.Gson
import com.polinalinen.madre.model.Recipe
import com.polinalinen.madre.model.RecipeDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStreamReader

/**
 * Единая точка чтения recipes.json. В v3 парсинг был размазан по ViewModel'ям —
 * в v4 выносим в repository layer (см. madre-v4-plan.md §4).
 * Чтение файла — на Dispatchers.IO, не на главном потоке (баг v3 #6).
 */
class RecipeRepository(private val context: Context) {

    private var cache: List<Recipe>? = null

    suspend fun getRecipes(): List<Recipe> = withContext(Dispatchers.IO) {
        cache ?: loadFromAssets().also { cache = it }
    }

    suspend fun getRecipe(id: String): Recipe? = getRecipes().find { it.id == id }

    private fun loadFromAssets(): List<Recipe> {
        context.assets.open("recipes.json").use { stream ->
            InputStreamReader(stream, Charsets.UTF_8).use { reader ->
                return Gson().fromJson(reader, RecipeDatabase::class.java).recipes
            }
        }
    }
}
