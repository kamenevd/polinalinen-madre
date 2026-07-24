package com.polinalinen.madre.data.repository

import com.polinalinen.madre.data.db.MadreDatabase
import com.polinalinen.madre.data.db.entities.SealedNoteEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * Запечатанные записки на будущее — DESIGN-V4.md Cycle 2, фича TimeCapsule.
 * IO — на Dispatchers.IO (баг v3 #6).
 */
class SealedNoteRepository(db: MadreDatabase) {
    private val dao = db.sealedNoteDao()

    fun observeForRecipe(recipeId: String): Flow<List<SealedNoteEntity>> = dao.observeForRecipe(recipeId)

    suspend fun seal(recipeId: String, text: String, unlockAfterBakes: Int): Long = withContext(Dispatchers.IO) {
        dao.insert(SealedNoteEntity(recipeId = recipeId, text = text, unlockAfterBakes = unlockAfterBakes))
    }

    suspend fun unlock(noteId: Long) = withContext(Dispatchers.IO) {
        dao.markUnlocked(noteId, System.currentTimeMillis())
    }
}
