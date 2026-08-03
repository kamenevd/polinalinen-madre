package com.polinalinen.madre.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.account.FamilyAccountRepository
import com.polinalinen.madre.account.FamilyBookState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI-facing coordinator for the optional online family book. Room stays separate. */
class FamilyBookViewModel(app: Application) : AndroidViewModel(app) {
    private val repository: FamilyAccountRepository = (app as MadreApplication).familyAccountRepository
    private val _state = MutableStateFlow<FamilyBookState>(FamilyBookState.SignedOut)
    val state: StateFlow<FamilyBookState> = _state.asStateFlow()

    fun restore() {
        if (_state.value is FamilyBookState.Loading || _state.value is FamilyBookState.SignedIn) return
        runNetwork { repository.restore() }
    }

    fun signIn(email: String, password: String) = runNetwork {
        repository.signIn(email.trim(), password)
    }

    fun register(email: String, password: String, displayName: String) = runNetwork {
        repository.register(email.trim(), password, displayName.trim())
    }

    fun createFamily(name: String) = runNetwork { repository.createFamily(name.trim()) }

    fun joinFamily(code: String) = runNetwork { repository.joinFamily(code) }

    fun rotateInviteCode() = runNetwork { repository.rotateInviteCode() }

    fun signOut() {
        _state.value = repository.signOut()
    }

    /** Keep the one-time invite code out of the screen state after it was shown. */
    fun clearInviteCode() {
        val current = _state.value
        if (current is FamilyBookState.SignedIn && current.account.inviteCode != null) {
            _state.value = current.copy(account = current.account.copy(inviteCode = null))
        }
    }

    private fun runNetwork(action: suspend () -> FamilyBookState) {
        if (_state.value is FamilyBookState.Loading) return
        viewModelScope.launch {
            _state.value = FamilyBookState.Loading
            _state.value = action()
        }
    }
}
