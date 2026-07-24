package com.polinalinen.madre.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.remote.PocketBaseFilter
import com.polinalinen.madre.model.LibraryNote
import com.polinalinen.madre.model.LibraryNotes
import com.polinalinen.madre.sync.DeviceIdentity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * «Библиотечная книга» на развороте рецепта (Cycle 6): один запрос чужих
 * margin_notes_sync по recipeId + bake_stats для подписи «семья N, X выпечек».
 * Ошибка сети — не ошибка UX: список остаётся пустым и поля молчат
 * (тот же принцип, что CommunityStatsViewModel).
 */
class LibraryNotesViewModel(app: Application) : AndroidViewModel(app) {

    private val api = (app as MadreApplication).madreApi

    private val _notes = MutableStateFlow<List<LibraryNote>>(emptyList())
    val notes: StateFlow<List<LibraryNote>> = _notes.asStateFlow()

    fun load(recipeId: String) {
        viewModelScope.launch {
            runCatching {
                val deviceId = DeviceIdentity.id(getApplication())
                val notes = api.listMarginNotes(PocketBaseFilter.recipeExcludingDevice(recipeId, deviceId))
                val stats = api.listBakeStats(PocketBaseFilter.excludeDevice(deviceId))
                LibraryNotes.from(notes.items, stats.items)
            }.onSuccess { _notes.value = it }
            // onFailure: молчим — PocketBase виден только из домашней сети,
            // вне её разворот рецепта не должен ни падать, ни ругаться.
        }
    }
}
