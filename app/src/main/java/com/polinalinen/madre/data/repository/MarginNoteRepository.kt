package com.polinalinen.madre.data.repository

import com.polinalinen.madre.data.db.MadreDatabase
import com.polinalinen.madre.data.db.entities.MarginNoteEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Пометы на полях рецепта — семейные заметки, привязанные к recipeId.
 * IO — на Dispatchers.IO (баг v3 #6).
 */
class MarginNoteRepository(db: MadreDatabase) {
    private val dao = db.marginNoteDao()

    fun observeForRecipe(recipeId: String): Flow<List<MarginNoteEntity>> = dao.observeForRecipe(recipeId)

    suspend fun addNote(recipeId: String, text: String): Long = withContext(Dispatchers.IO) {
        dao.insert(MarginNoteEntity(recipeId = recipeId, text = text))
    }
}
