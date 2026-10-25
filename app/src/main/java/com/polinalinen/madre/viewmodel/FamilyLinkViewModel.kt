package com.polinalinen.madre.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.account.FamilyAccountRepository
import com.polinalinen.madre.account.FamilyBookState
import com.polinalinen.madre.integration.v2.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface FamilyOverlay {
    data object Invite : FamilyOverlay
    data object CreatedInvite : FamilyOverlay
    data object Create : FamilyOverlay
    data object Rename : FamilyOverlay
    data class Member(val id: String) : FamilyOverlay
    data class Remove(val id: String) : FamilyOverlay
    data class Transfer(val id: String) : FamilyOverlay
    data object Leave : FamilyOverlay
}

/** Activity-owned, retained across rotation. SavedState whitelists only validated INVITE. */
class FamilyLinkViewModel internal constructor(app: Application, private val saved: SavedStateHandle,
    private val repository: FamilyLinkRepository, private val accounts: FamilyAccountRepository,
    enabled: Boolean = com.polinalinen.madre.config.FeatureFlags.FAMILY_ROSTER_V2,
) : AndroidViewModel(app) {
    constructor(app: Application, saved: SavedStateHandle) : this(app, saved,
        (app as MadreApplication).familyLinkRepository, app.familyAccountRepository)
    val state = repository.state
    val invitation = repository.invitation.states
    private val mutableOverlay = MutableStateFlow<FamilyOverlay?>(null)
    val overlay = mutableOverlay.asStateFlow()
    private val flight = AtomicBoolean(false)
    private var work: Job? = null
    private var workGeneration = 0L
    private var expiry: Job? = null
    init {
        if (enabled) {
            saved.get<String>(INVITE)?.let(::openInvitation)
            viewModelScope.launch {
                accounts.state.collect { account ->
                    if (account is FamilyBookState.SignedOut && !accounts.hasSavedSession()) {
                        val url = repository.invitation.savedInviteUrl()
                        work?.cancel(); flight.set(false); repository.clear(); mutableOverlay.value = null
                        if (url != null) openInvitation(url)
                    }
                }
            }
        }
    }
    fun show(overlay: FamilyOverlay?) { mutableOverlay.value = overlay }
    fun refresh() = run {
        if (mutableOverlay.value == FamilyOverlay.CreatedInvite || mutableOverlay.value == FamilyOverlay.Invite)
            repository.loadInvite()
        else repository.refresh()
    }
    fun openInvitation(url: String): Job {
        val validated = FamilyInvitation.parse(url)
        workGeneration++; work?.cancel(); flight.set(false); repository.invitation.clear(); mutableOverlay.value = null
        if (validated == null) saved.remove<String>(INVITE) else saved[INVITE] = validated.savedInviteUrl()
        work = viewModelScope.launch {
            // Preview remains public; auth failure does not substitute another identity.
            val session = if (validated == null) null else try { repository.invitationSession() } catch (c: CancellationException) { throw c } catch (_: Exception) { null }
            repository.invitation.open(validated?.savedInviteUrl() ?: "", session)
        }
        return checkNotNull(work)
    }
    fun closeInvitation() {
        workGeneration++; work?.cancel(); flight.set(false); repository.invitation.clear(); saved.remove<String>(INVITE)
    }
    fun signedInReturned() = run {
        val session = repository.invitationSession()
        if (session != null && invitation.value.phase == InvitationFlow.Phase.SIGN_IN)
            repository.invitation.returnedFromSignIn(session)
        repository.refresh()
    }
    fun join(confirmSwitch: Boolean = false) = run {
        repository.invitation.join(confirmSwitch)
        if (invitation.value.phase == InvitationFlow.Phase.MEMBER) repository.refresh()
    }
    fun refreshPending() = run {
        repository.invitation.refreshPending()
        if (invitation.value.phase == InvitationFlow.Phase.MEMBER) repository.refresh()
    }
    fun retryInvitation() { repository.invitation.savedInviteUrl()?.let(::openInvitation) }
    fun create(name: String) = run {
        repository.create(name)
        if (state.value.failure == null && state.value.family != null) {
            mutableOverlay.value = FamilyOverlay.CreatedInvite
            repository.loadInvite()
        }
    }
    fun rename(name: String) = run { repository.renameFamily(name); dismissOnSuccess() }
    fun approval(required: Boolean) = run { repository.approval(required) }
    fun renameMember(id: String, name: String) = run { repository.renameMember(id, name); dismissOnSuccess() }
    fun transfer(id: String) = run { repository.transfer(id); dismissOnSuccess() }
    fun decide(id: String, accept: Boolean) = run { repository.decide(id, accept) }
    fun leave(newOwner: String? = null) = run { repository.leave(newOwner); dismissOnSuccess() }
    fun delete() = run { repository.delete(); dismissOnSuccess() }
    fun remove(id: String, rotate: Boolean) = run {
        repository.remove(id, rotate); dismissOnSuccess()
        expiry?.cancel()
        expiry = viewModelScope.launch { delay(repository.undoRemaining()); repository.expireUndo() }
    }
    fun undo() = run { repository.restoreRemoved() }
    fun invite(rotate: Boolean = false) = run { mutableOverlay.value = FamilyOverlay.Invite; repository.loadInvite(rotate) }
    fun revoke() = run { repository.revokeInvite() }
    private fun dismissOnSuccess() { if (state.value.failure == null) mutableOverlay.value = null }
    private fun run(action: suspend () -> Unit): Job? {
        if (!flight.compareAndSet(false, true)) return null
        val generation = ++workGeneration
        return viewModelScope.launch(start = CoroutineStart.LAZY) {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { repository.fail((error as? ContractFailure)?.kind ?: ContractFailure.Kind.INVALID) }
            finally { if (workGeneration == generation) flight.set(false) }
        }.also { job -> work = job; job.invokeOnCompletion { if (workGeneration == generation) flight.set(false) }; job.start() }
    }
    override fun onCleared() { expiry?.cancel(); repository.clear(); super.onCleared() }
    companion object { const val INVITE = "madre.family.validated_invite" }
}
