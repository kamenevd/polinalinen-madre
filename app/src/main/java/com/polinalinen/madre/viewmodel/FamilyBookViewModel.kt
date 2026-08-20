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

    val state: StateFlow<FamilyBookState> = repository.state

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
        if (repository.state.value is FamilyBookState.Loading || repository.state.value is FamilyBookState.SignedIn) return
        runNetwork { repository.restore() }
    }

    /** Keep the one-time invite code out of both the screen and the repository. */
    fun clearInviteCode() {
        repository.clearInviteCode()
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

    fun refreshFamily() = runNetwork { repository.refresh() }

    fun signOut() {
        repository.signOut()
    }

    private fun runNetwork(action: suspend () -> FamilyBookState) {
        // Взвести флаг до launch: compareAndSet впускает ровно первого и не
        // даёт отправить второй такой же запрос, пока первый ещё в пути.
        if (!inFlight.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                action()
            } finally {
                inFlight.set(false)
            }
        }
    }
}
