package com.polinalinen.madre.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.db.entities.MarginNoteEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Пометы на полях рецепта — DESIGN-V4.md Cycle 1, фича «Пометы на полях». */
class MarginNoteViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as MadreApplication).marginNoteRepository
    private val syncRepository = (app as MadreApplication).syncRepository

    fun notesFor(recipeId: String): Flow<List<MarginNoteEntity>> = repository.observeForRecipe(recipeId)

    fun addNote(recipeId: String, text: String) {
        viewModelScope.launch {
            val noteId = repository.addNote(recipeId, text)
            // Cycle 5: заметка синхронизируется в margin_notes_sync фоном.
            syncRepository.shareMarginNote(noteId, recipeId, text, System.currentTimeMillis())
        }
    }
}
