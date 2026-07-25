package com.polinalinen.madre.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.remote.PocketBaseFilter
import com.polinalinen.madre.model.GuestNote
import com.polinalinen.madre.model.GuestPage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * «Гостевая страница» рецепта (Cycle 7): отзывы гостей из PocketBase
 * guest_notes. Ошибка сети — не ошибка UX: список пуст и страница молчит
 * (тот же принцип, что LibraryNotesViewModel).
 */
class GuestNotesViewModel(app: Application) : AndroidViewModel(app) {

    private val api = (app as MadreApplication).madreApi

    private val _notes = MutableStateFlow<List<GuestNote>>(emptyList())
    val notes: StateFlow<List<GuestNote>> = _notes.asStateFlow()

    fun load(recipeId: String) {
        viewModelScope.launch {
            runCatching {
                GuestPage.from(api.listGuestNotes(PocketBaseFilter.forRecipe(recipeId)).items)
            }.onSuccess { _notes.value = it }
            // onFailure: молчим — вне домашней сети гостевая страница просто пуста.
        }
    }
}
