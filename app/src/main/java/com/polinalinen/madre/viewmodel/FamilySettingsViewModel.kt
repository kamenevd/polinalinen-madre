package com.polinalinen.madre.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.ui.components.BookplateName
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Имя семьи для экслибриса — DESIGN-V4.md Cycle 3, фича «Экслибрис» (Bookplate). */
class FamilySettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as MadreApplication).familySettingsRepository
    val bookDisplaySettings = (app as MadreApplication).bookDisplaySettings
    private val _displayError = MutableStateFlow<String?>(null)
    val displayError = _displayError.asStateFlow()

    val familyName: StateFlow<String?> = repository.observeFamilyName()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setFamilyName(name: String) {
        val sanitized = BookplateName.sanitize(name)
        if (sanitized.isEmpty()) return
        viewModelScope.launch { repository.setFamilyName(sanitized) }
    }

    fun setKeepScreenOn(value: Boolean) = saveDisplaySetting { repository.setKeepScreenOn(value) }
    fun setLockScreenProgress(value: Boolean) = saveDisplaySetting { repository.setLockScreenProgress(value) }
    private fun saveDisplaySetting(write: suspend () -> Unit) = viewModelScope.launch {
        try { write(); _displayError.value = null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { _displayError.value = "Не удалось сохранить настройку. Повторите выбор; прежнее значение сохранено." }
    }
}
