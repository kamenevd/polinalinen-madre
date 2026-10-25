package com.polinalinen.madre.integration.v2

import kotlinx.coroutines.CancellationException

/** No OAuth in state. Only savedInviteUrl may enter SavedState; nothing auto-joins after restore/sign-in. */
class InvitationFlow(private val api: FamilyLinkApi) {
    enum class Phase { EMPTY, LOADING, SIGN_IN, READY, JOINING, SWITCH_CONFIRMATION, PENDING, VERIFYING, MEMBER, DECLINED, ERROR }
    data class State(val phase: Phase = Phase.EMPTY, val preview: InvitePreview? = null,
                     val failure: ContractFailure.Kind? = null, val family: FamilyRoster? = null)
    private var generation = 0L
    private var invite: FamilyInvitation? = null
    private var session: FamilySession? = null
    private var pending: JoinReply? = null
    private val observable = kotlinx.coroutines.flow.MutableStateFlow(State())
    val states: kotlinx.coroutines.flow.StateFlow<State> = observable
    private var current = State()
        set(value) { field = value; observable.value = value }
    @Synchronized fun state(): State = current
    @Synchronized fun savedInviteUrl(): String? = invite?.savedInviteUrl()

    suspend fun open(url: String, signedIn: FamilySession? = null) {
        val parsed = FamilyInvitation.parse(url)
        val epoch = synchronized(this) {
            generation++; invite = parsed; session = signedIn; pending = null
            current = State(if (parsed == null) Phase.ERROR else Phase.LOADING,
                failure = if (parsed == null) ContractFailure.Kind.INVALID else null)
            generation
        }
        if (parsed == null) return
        perform(epoch) {
            val preview = api.preview(parsed)
            synchronized(this) { if (generation == epoch) current = State(if (session == null) Phase.SIGN_IN else Phase.READY, preview) }
        }
    }

    /** A successful Alice bind is NOT sufficient: caller must pass the verified PB session. */
    @Synchronized fun returnedFromSignIn(verifiedPocketBaseSession: FamilySession) {
        check(current.phase == Phase.SIGN_IN)
        session = verifiedPocketBaseSession
        current = current.copy(phase = Phase.READY)
    }

    /** Explicit UI click only. A separate explicit confirmation is required following OTHER_FAMILY. */
    suspend fun join(confirmSwitch: Boolean = false) {
        val work = synchronized(this) {
            check(current.phase == Phase.READY || current.phase == Phase.SWITCH_CONFIRMATION)
            check(confirmSwitch == (current.phase == Phase.SWITCH_CONFIRMATION))
            current = current.copy(phase = Phase.JOINING, failure = null)
            Triple(generation, checkNotNull(invite), checkNotNull(session))
        }
        perform(work.first) {
            val reply = api.join(work.second, work.third, confirmSwitch)
            acceptReply(work.first, reply, work.third)
        }
    }

    /** Foreground refresh only, not an infinite poll or an offline membership claim. */
    suspend fun refreshPending() {
        val work = synchronized(this) {
            check(current.phase == Phase.PENDING)
            current = current.copy(phase = Phase.VERIFYING, failure = null)
            Triple(generation, checkNotNull(session), checkNotNull(pending))
        }
        perform(work.first) {
            val reply = api.status(checkNotNull(invitationFor(work.first)), work.second, checkNotNull(work.third.requestId))
            require(reply.familyId == work.third.familyId && reply.requestId == work.third.requestId)
            acceptReply(work.first, reply, work.second)
        }
    }

    @Synchronized private fun invitationFor(epoch: Long): FamilyInvitation? = invite.takeIf { generation == epoch }

    private suspend fun acceptReply(epoch: Long, reply: JoinReply, account: FamilySession) {
        synchronized(this) {
            if (generation != epoch) return
            pending = reply
            current = current.copy(phase = when (reply.status) {
                JoinStatus.PENDING -> Phase.PENDING
                JoinStatus.DECLINED -> Phase.DECLINED
                JoinStatus.JOINED -> Phase.VERIFYING
            })
        }
        if (reply.status != JoinStatus.JOINED) return
        // Join acknowledgement alone never grants access: authoritative authenticated membership readback.
        val family = api.family(account)
        require(family.id == reply.familyId && family.members.any { it.id == account.userId && it.status == MembershipStatus.JOINED })
        synchronized(this) { if (generation == epoch) current = current.copy(phase = Phase.MEMBER, family = family) }
    }

    /** Sign-out/removal/new deep link invalidates outstanding responses and any claimed access. */
    @Synchronized fun clear() {
        generation++; session = null; invite = null; pending = null; current = State()
    }

    private suspend fun perform(epoch: Long, block: suspend () -> Unit) {
        try { block() }
        catch (cancelled: CancellationException) {
            synchronized(this) { if (generation == epoch) current = current.copy(phase = Phase.ERROR, failure = ContractFailure.Kind.NETWORK, family = null) }
            throw cancelled
        }
        catch (error: Exception) {
            val kind = (error as? ContractFailure)?.kind ?: ContractFailure.Kind.INVALID
            synchronized(this) {
                if (generation == epoch) current = current.copy(
                    phase = if (kind == ContractFailure.Kind.OTHER_FAMILY && current.phase == Phase.JOINING) Phase.SWITCH_CONFIRMATION else Phase.ERROR,
                    failure = kind, family = null)
            }
        }
    }
}
