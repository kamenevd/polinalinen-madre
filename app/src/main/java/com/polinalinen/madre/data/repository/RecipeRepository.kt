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
    @Volatile var revision: String? = null
        private set

    suspend fun getRecipes(): List<Recipe> = withContext(Dispatchers.IO) {
        cache ?: loadFromAssets().also { cache = it }
    }

    suspend fun getRecipe(id: String): Recipe? = getRecipes().find { it.id == id }

    private fun loadFromAssets(): List<Recipe> {
        val bytes = context.assets.open("recipes.json").use { it.readBytes() }
        val recipes = Gson().fromJson(bytes.toString(Charsets.UTF_8), RecipeDatabase::class.java).recipes
        revision = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        return recipes
    }
}
