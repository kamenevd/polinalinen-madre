package com.polinalinen.madre.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.account.FamilyAccountRepository
import com.polinalinen.madre.account.FamilyBookState
import com.polinalinen.madre.account.PasswordResetNotice
import com.polinalinen.madre.account.PasswordResetResult
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI-facing coordinator for the optional online family book. Room stays separate. */
class FamilyBookViewModel internal constructor(
    app: Application,
    private val repository: FamilyAccountRepository,
) : AndroidViewModel(app) {
    constructor(app: Application) : this(app, (app as MadreApplication).familyAccountRepository)

    private val _state = MutableStateFlow<FamilyBookState>(FamilyBookState.SignedOut)
    val state: StateFlow<FamilyBookState> = _state.asStateFlow()

    private val _passwordReset = MutableStateFlow<PasswordResetNotice>(PasswordResetNotice.Idle)
    val passwordReset: StateFlow<PasswordResetNotice> = _passwordReset.asStateFlow()

    /**
     * Ровно один сетевой запрос за раз. Проверять по [_state] нельзя: Loading
     * ставился внутри launch и до его исполнения два быстрых тапа успевали
     * проскочить мимо, отправив создание/ротацию дважды. Флаг взводится
     * синхронно, до launch, и снимается гарантированно.
     */
    private val inFlight = AtomicBoolean(false)

    fun restore() {
        if (_state.value is FamilyBookState.Loading || _state.value is FamilyBookState.SignedIn) return
        runNetwork { repository.restore() }
    }

    /** Keep the one-time invite code out of both the screen and the repository. */
    fun clearInviteCode() {
        repository.clearInviteCode()
        when (val current = _state.value) {
            is FamilyBookState.SignedIn ->
                if (current.account.inviteCode != null) {
                    _state.value = current.copy(account = current.account.copy(inviteCode = null))
                }
            is FamilyBookState.Failed -> {
                val account = current.account
                if (account?.inviteCode != null) {
                    _state.value = current.copy(account = account.copy(inviteCode = null))
                }
            }
            else -> Unit
        }
    }

    fun signIn(email: String, password: String) = runNetwork {
        repository.signIn(email.trim(), password)
    }

    /**
     * Сброс пароля не переводит книгу в Loading: форма должна остаться
     * на месте, а локальная книга — не дёрнуться.
     */
    fun requestPasswordReset(email: String) {
        if (!inFlight.compareAndSet(false, true)) return
        _passwordReset.value = PasswordResetNotice.Sending
        viewModelScope.launch {
            try {
                _passwordReset.value = when (val result = repository.requestPasswordReset(email)) {
                    PasswordResetResult.Sent -> PasswordResetNotice.Sent
                    is PasswordResetResult.Failed -> PasswordResetNotice.Failed(result.message)
                }
            } finally {
                inFlight.set(false)
            }
        }
    }

    fun register(email: String, password: String, displayName: String) = runNetwork {
        repository.register(email.trim(), password, displayName.trim())
    }

    fun createFamily(name: String) = runNetwork { repository.createFamily(name.trim()) }

    fun joinFamily(code: String) = runNetwork { repository.joinFamily(code) }

    fun rotateInviteCode() = runNetwork { repository.rotateInviteCode() }

    fun renameFamily(name: String) = runNetwork { repository.renameFamily(name) }

    fun leaveFamily() = runNetwork { repository.leaveFamily() }

    fun signOut() {
        _state.value = repository.signOut()
    }

    private fun runNetwork(action: suspend () -> FamilyBookState) {
        // Взвести флаг до launch: guard по _state пропускал двойной тап, пока
        // Loading ещё не выставлен. compareAndSet впускает ровно первого.
        if (!inFlight.compareAndSet(false, true)) return
        _state.value = FamilyBookState.Loading
        viewModelScope.launch {
            try {
                _state.value = action()
            } finally {
                inFlight.set(false)
            }
        }
    }
}
