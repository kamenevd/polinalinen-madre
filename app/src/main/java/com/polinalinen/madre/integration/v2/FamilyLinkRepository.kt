package com.polinalinen.madre.integration.v2

import com.polinalinen.madre.account.FamilyAccountRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.withLock

/** Sanitized presentation model: no session/restore credential, no offline authority. */
data class FamilyPanelState(
    val busy: Boolean = false, val signedIn: Boolean = false, val migration: Boolean = false,
    val userId: String? = null, val accountName: String = "", val family: FamilyRoster? = null,
    val invite: ActiveFamilyInvite? = null, val failure: ContractFailure.Kind? = null,
    val removedFamilyName: String? = null, val undoName: String? = null,
) {
    val owner: Boolean get() = family?.owner == userId && userId != null
}

/** Network-only family coordinator. All session I/O shares the existing account mutex. */
class FamilyLinkRepository(
    private val accounts: FamilyAccountRepository,
    private val auth: FamilyAuthenticationApi,
    private val links: FamilyLinkApi,
    private val management: FamilyManagementApi,
    private val elapsed: () -> Long = android.os.SystemClock::elapsedRealtime,
) {
    private val mutable = MutableStateFlow(FamilyPanelState())
    val state = mutable.asStateFlow()
    @Volatile private var epoch = 0L
    private val lane = kotlinx.coroutines.sync.Mutex()
    private var activeEpoch = 0L
    private var accountEpoch = 0L
    private fun publish(next: FamilyPanelState) {
        if (epoch != activeEpoch || accountEpoch != accounts.sessionGeneration()) throw CancellationException()
        mutable.value = next
    }
    private var undo: Undo? = null
    private class Undo(val familyId: String, val userId: String, val memberId: String,
        val receipt: RemovedFamilyMember, var deadline: Long)

    /** InvitationFlow uses fresh protected credentials, not the credential captured on opening. */
    val invitation = InvitationFlow(object : FamilyLinkApi {
        override suspend fun preview(invite: FamilyInvitation) = links.preview(invite)
        override suspend fun join(invite: FamilyInvitation, session: FamilySession, switchFamily: Boolean) =
            verified(session.userId) { links.join(invite, it, switchFamily) }.first
        override suspend fun status(invite: FamilyInvitation, session: FamilySession, requestId: String) =
            verified(session.userId) { links.status(invite, it, requestId) }.first
        override suspend fun family(session: FamilySession): FamilyRoster {
            val (roster, fresh) = verified(session.userId) { links.family(it) }
            verifyRoster(roster, fresh)
            accounts.adoptFamilyReadback(roster)
            return roster
        }
    })

    suspend fun invitationSession(): FamilySession? {
        if (!accounts.hasYandexSession()) return null
        val version = epoch
        val accountVersion = accounts.sessionGeneration()
        return accounts.familyTransaction(auth) { Unit }.second.also {
            if (version != epoch || accountVersion != accounts.sessionGeneration()) throw CancellationException()
            mutable.value = mutable.value.copy(signedIn = true, userId = it.userId, accountName = it.name)
        }
    }

    private suspend fun <T> verified(userId: String? = null, action: suspend (FamilySession) -> T): Pair<T, FamilySession> {
        if (!accounts.hasYandexSession()) throw ContractFailure(ContractFailure.Kind.UNAUTHORIZED)
        return accounts.familyTransaction(auth) {
            if (userId != null) require(userId == it.userId)
            action(it)
        }
    }
    private fun verifyRoster(roster: FamilyRoster, session: FamilySession) {
        require(session.familyId == roster.id && roster.members.any { it.id == session.userId && it.status == MembershipStatus.JOINED })
        require(roster.owner == session.userId || roster.pending.isEmpty())
    }

    fun fail(kind: ContractFailure.Kind) { mutable.value = mutable.value.copy(busy = false, family = null, invite = null, failure = kind) }

    fun clear() {
        epoch++; undo = null; invitation.clear(); mutable.value = FamilyPanelState()
    }

    suspend fun refresh() = operation { session ->
        val old = pendingPrevious?.family
        val (_, fresh) = verified { Unit }
        val roster = if (fresh.familyId != null) links.family(fresh).also { verifyRoster(it, fresh) } else null
        if (roster != null) accounts.adoptFamilyReadback(roster)
        if (undo?.let { it.familyId != roster?.id || it.userId != roster.owner || it.userId != fresh.userId } == true) {
            undo = null
            publish(mutable.value.copy(undoName = null))
        }
        publish(mutable.value.copy(signedIn = true, userId = fresh.userId, accountName = fresh.name,
            family = roster, removedFamilyName = if (old != null && roster == null) old.name else mutable.value.removedFamilyName))
    }

    /** Invalidates visible authority while any mutation/read is outstanding. */
    private suspend fun operation(action: suspend (FamilySession?) -> Unit) = lane.withLock {
        val version = epoch
        activeEpoch = version
        accountEpoch = accounts.sessionGeneration()
        val previous = mutable.value
        publish(previous.copy(busy = true, family = null, invite = null, failure = null))
        try {
            if (!accounts.hasYandexSession()) {
                publish(FamilyPanelState(migration = accounts.hasSavedSession()))
                return@withLock
            }
            // Keep previous information private to this call; UI cannot use it as offline access.
            pendingPrevious = previous
            action(null)
        } catch (cancelled: CancellationException) {
            undo = null
            if (version == epoch) mutable.value = if (accountEpoch == accounts.sessionGeneration()) mutable.value.copy(
                family = null, invite = null, undoName = null, failure = ContractFailure.Kind.NETWORK)
            else FamilyPanelState(failure = ContractFailure.Kind.UNAUTHORIZED)
            throw cancelled
        }
        catch (failure: Exception) {
            undo = null
            if (version == epoch && accountEpoch == accounts.sessionGeneration()) mutable.value = mutable.value.copy(family = null, invite = null, undoName = null,
                failure = (failure as? ContractFailure)?.kind ?: ContractFailure.Kind.INVALID)
        } finally {
            pendingPrevious = null
            if (version == epoch) mutable.value = mutable.value.copy(busy = false)
        }
    }
    private var pendingPrevious: FamilyPanelState? = null

    private suspend fun rosterAfter(session: FamilySession): FamilyRoster? {
        if (session.familyId == null) return null
        return links.family(session).also { verifyRoster(it, session); accounts.adoptFamilyReadback(it) }
    }
    private suspend fun mutate(expect: (FamilyRoster?) -> Unit = {}, action: suspend (FamilySession, FamilyRoster?) -> Unit) = operation {
        val prior = pendingPrevious
        val (_, fresh) = verified { live ->
            val roster = if (live.familyId == null) null else links.family(live).also { verifyRoster(it, live) }
            action(live, roster)
        }
        val roster = rosterAfter(fresh)
        expect(roster)
        if (undo != null && mutable.value.undoName != null && prior?.undoName == null) undo?.deadline = elapsed() + 8_000
        if (prior?.family?.owner != roster?.owner || prior?.family?.id != roster?.id) undo = null
        publish(mutable.value.copy(signedIn = true, userId = fresh.userId, accountName = fresh.name,
            family = roster, undoName = if (undo == null) null else mutable.value.undoName))
    }
    private fun owner(session: FamilySession, roster: FamilyRoster?): FamilyRoster = requireNotNull(roster).also {
        if (it.owner != session.userId) throw ContractFailure(ContractFailure.Kind.UNAUTHORIZED)
    }
    suspend fun create(name: String) = mutate(expect = { require(it?.name == name) }) { session, roster -> require(roster == null); management.create(session, name) }
    suspend fun renameFamily(name: String) = mutate(expect = { require(it?.name == name) }) { session, roster -> owner(session, roster); management.update(session, name = name) }
    suspend fun approval(required: Boolean) = mutate(expect = { require(it?.approvalRequired == required) }) { session, roster -> owner(session, roster); management.update(session, approvalRequired = required) }
    suspend fun renameMember(id: String, name: String) = mutate(expect = { require(it?.members?.singleOrNull { member -> member.id == id }?.name == name) }) { session, roster ->
        requireNotNull(roster); require(id == session.userId || roster.owner == session.userId)
        management.renameMember(session, id, name)
    }
    suspend fun transfer(id: String) = mutate(expect = { require(it?.owner == id) }) { session, roster -> owner(session, roster); management.transfer(session, id); undo = null }
    suspend fun decide(id: String, accept: Boolean) {
        val applicant = state.value.family?.pending?.singleOrNull { it.id == id }?.userId
        mutate(expect = { fresh ->
            require(fresh != null && fresh.pending.none { it.id == id })
            if (accept) require(applicant != null && fresh.members.any { it.id == applicant })
        }) { session, roster -> owner(session, roster); management.decide(session, id, accept) }
    }
    suspend fun leave(newOwner: String? = null) = mutate(expect = { require(it == null) }) { session, roster ->
        requireNotNull(roster)
        require(roster.owner != session.userId || (newOwner != null && roster.members.any { it.id == newOwner && it.id != session.userId }))
        management.leave(session, newOwner); undo = null
    }
    suspend fun delete() = mutate(expect = { require(it == null) }) { session, roster -> require(owner(session, roster).members.size == 1); management.delete(session); undo = null }
    suspend fun remove(id: String, rotate: Boolean) = mutate(expect = { require(it != null && it.members.none { member -> member.id == id }) }) { session, roster ->
        val family = owner(session, roster); require(id != session.userId)
        val member = family.members.single { it.id == id }
        val receipt = management.remove(session, id, rotate)
        undo = Undo(family.id, session.userId, id, receipt, elapsed() + 8_000)
        publish(mutable.value.copy(undoName = member.name))
    }
    suspend fun restoreRemoved() {
        val target = undo?.memberId
        mutate(expect = { require(target != null && it?.members?.any { member -> member.id == target } == true) }) { session, roster ->
        val saved = requireNotNull(undo)
        require(elapsed() < saved.deadline && saved.familyId == roster?.id && saved.userId == session.userId)
        owner(session, roster); undo = null
        management.restoreMember(session, saved.memberId, saved.receipt)
        publish(mutable.value.copy(undoName = null))
        }
    }
    fun expireUndo() { undo = null; mutable.value = mutable.value.copy(undoName = null) }
    fun undoRemaining(): Long = undo?.let { (it.deadline - elapsed()).coerceAtLeast(0) } ?: 0

    /** Member may GET/share only. A missing link may be created only for an owner. */
    suspend fun loadInvite(rotate: Boolean = false) = mutate { session, roster ->
        requireNotNull(roster)
        if (rotate) owner(session, roster)
        val link = try { management.invite(session, rotate) }
        catch (error: ContractFailure) {
            if (!rotate && error.kind == ContractFailure.Kind.DEAD_INVITE && roster.owner == session.userId) management.invite(session, true)
            else throw error
        }
        val readback = management.invite(session, false)
        require(readback.invitation.savedInviteUrl() == link.invitation.savedInviteUrl() && readback.expiresAt == link.expiresAt)
        publish(mutable.value.copy(invite = readback))
    }
    suspend fun revokeInvite() = mutate { session, roster ->
        owner(session, roster); management.revokeInvite(session)
        // Real readback: successful DELETE is not evidence that the link is no longer readable.
        try { management.invite(session); throw ContractFailure(ContractFailure.Kind.CONFLICT) }
        catch (error: ContractFailure) { if (error.kind != ContractFailure.Kind.DEAD_INVITE) throw error }
        publish(mutable.value.copy(invite = null))
    }
}
