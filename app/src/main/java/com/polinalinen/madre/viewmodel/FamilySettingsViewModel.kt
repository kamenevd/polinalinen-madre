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

/** Имя семьи для экслибриса — DESIGN-V4.md Cycle 3, фича «Экслибрис» (Bookplate). */
class FamilySettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val repository = (app as MadreApplication).familySettingsRepository

    val familyName: StateFlow<String?> = repository.observeFamilyName()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setFamilyName(name: String) {
        val sanitized = BookplateName.sanitize(name)
        if (sanitized.isEmpty()) return
        viewModelScope.launch { repository.setFamilyName(sanitized) }
    }
}
