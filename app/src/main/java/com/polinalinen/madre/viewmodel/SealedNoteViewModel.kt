package com.polinalinen.madre.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.db.entities.SealedNoteEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Конверты на будущее — DESIGN-V4.md Cycle 2, фича «Конверт на будущее» (TimeCapsule). */
class SealedNoteViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as MadreApplication).sealedNoteRepository
    private val bakeHistoryRepository = (app as MadreApplication).bakeHistoryRepository

    fun notesFor(recipeId: String): Flow<List<SealedNoteEntity>> = repository.observeForRecipe(recipeId)

    /** Сколько раз именно этот рецепт уже испечён — против него сверяется unlockAfterBakes. */
    fun bakeCountFor(recipeId: String): Flow<Int> =
        bakeHistoryRepository.observeAll().map { records -> records.count { it.recipeId == recipeId } }

    fun seal(recipeId: String, text: String, unlockAfterBakes: Int) {
        viewModelScope.launch { repository.seal(recipeId, text, unlockAfterBakes) }
    }

    fun unlock(noteId: Long) {
        viewModelScope.launch { repository.unlock(noteId) }
    }
}
