package com.polinalinen.madre.integration.v2

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic API fixtures exercise the real state machine, never backend acceptance. */
class InvitationFlowTest {
    private val url = "https://kdnfx.space/madre/f/Abcd0123_-Efgh45"
    private val account = FamilySession("user1", "Имя", null, "SYNTHETIC_PB_CREDENTIAL")
    private val preview = InvitePreview("Семья", "Имя", 1, 2, listOf("И"), false)
    private fun family(user: String = account.userId) = FamilyRoster("family1", "Семья", user, false,
        listOf(FamilyMember(user, "Имя", FamilyRole.OWNER, MembershipStatus.JOINED, "2026-10-25T00:00:00Z", 2)), emptyList())
    private inner class Api : FamilyLinkApi {
        var joins = 0; var reads = 0; var switches = mutableListOf<Boolean>()
        var reply = JoinReply(JoinStatus.JOINED, "family1", null)
        var roster = family(); var error: ContractFailure.Kind? = null
        var wait: CompletableDeferred<Unit>? = null
        var familyWait: CompletableDeferred<Unit>? = null
        override suspend fun preview(invite: FamilyInvitation): InvitePreview { wait?.await(); return preview }
        override suspend fun join(invite: FamilyInvitation, session: FamilySession, switchFamily: Boolean): JoinReply {
            joins++; switches.add(switchFamily)
            error?.let { throw ContractFailure(it) }
            return reply
        }
        override suspend fun status(invite: FamilyInvitation, session: FamilySession, requestId: String) = reply
        override suspend fun family(session: FamilySession): FamilyRoster { reads++; familyWait?.await(); return roster }
    }

    @Test fun exact96BitUrlOnlyAndNoNormalizationOrSecretToString() {
        val valid = checkNotNull(FamilyInvitation.parse(url))
        assertEquals(url, valid.savedInviteUrl()); assertFalse(valid.toString().contains("Abcd"))
        listOf(url+"/", url+"?a=1", url+"#x", " $url", url+"\n", url.replace("https:","http:"),
            url.replace("kdnfx.space", "KDNFX.SPACE"), url.replace("kdnfx.space", "kdnfx.space:443"),
            url.replace("kdnfx.space", "kdnfx.space.evil"), url.replace("kdnfx.space", "evil@kdnfx.space"),
            url.replace("/madre/f/", "/madre/f/../f/"), url.replace("Abcd", "%41bcd"),
            url.dropLast(1), url+"A", url.replace("_-", "+/")).forEach { assertNull(FamilyInvitation.parse(it)) }
    }
    @Test fun signInReturnAndRestoreKeepSameInvitationButNeverJoin() = runBlocking {
        val api = Api(); val flow = InvitationFlow(api)
        flow.open(url); assertEquals(InvitationFlow.Phase.SIGN_IN, flow.state().phase)
        flow.returnedFromSignIn(account); assertEquals(InvitationFlow.Phase.READY, flow.state().phase)
        assertEquals(0, api.joins); assertEquals(url, flow.savedInviteUrl())
        val restored = InvitationFlow(api); restored.open(checkNotNull(flow.savedInviteUrl()), account)
        assertEquals(InvitationFlow.Phase.READY, restored.state().phase); assertEquals(0, api.joins)
        restored.join(); assertEquals(1, api.joins); assertEquals(1, api.reads)
        assertEquals(InvitationFlow.Phase.MEMBER, restored.state().phase)
    }
    @Test fun pendingIsNotMembershipAndApprovalNeedsReadback() = runBlocking {
        val api = Api().apply { reply = JoinReply(JoinStatus.PENDING, "family1", "request1") }
        val flow = InvitationFlow(api); flow.open(url, account); flow.join()
        assertEquals(InvitationFlow.Phase.PENDING, flow.state().phase); assertNull(flow.state().family)
        flow.refreshPending(); assertEquals(InvitationFlow.Phase.PENDING, flow.state().phase); assertEquals(0, api.reads)
        api.reply = JoinReply(JoinStatus.JOINED, "family1", "request1"); flow.refreshPending()
        assertEquals(InvitationFlow.Phase.MEMBER, flow.state().phase); assertEquals(1, api.reads)
    }
    @Test fun declinedAndCrossRequestApprovalDoNotGrantMembership() = runBlocking {
        for (reply in listOf(JoinReply(JoinStatus.DECLINED,"family1","request1"), JoinReply(JoinStatus.JOINED,"family2","request1"), JoinReply(JoinStatus.JOINED,"family1","other"))) {
            val api = Api().apply { this.reply = JoinReply(JoinStatus.PENDING,"family1","request1") }
            val flow = InvitationFlow(api); flow.open(url,account); flow.join(); api.reply=reply; flow.refreshPending()
            assertNotEquals(InvitationFlow.Phase.MEMBER,flow.state().phase); assertNull(flow.state().family)
        }
    }
    @Test fun switchRequiresSeparateExplicitConfirmationAndNoStandaloneLeave() = runBlocking {
        val api = Api().apply { error = ContractFailure.Kind.OTHER_FAMILY }
        val flow = InvitationFlow(api); flow.open(url,account)
        assertTrue(runCatching { flow.join(true) }.isFailure); assertEquals(0,api.joins)
        flow.join(); assertEquals(InvitationFlow.Phase.SWITCH_CONFIRMATION,flow.state().phase)
        assertTrue(runCatching { flow.join() }.isFailure); assertEquals(1,api.joins)
        api.error=null; flow.join(true); assertEquals(listOf(false,true),api.switches)
        assertEquals(InvitationFlow.Phase.MEMBER,flow.state().phase)
    }
    @Test fun wrongAuthenticatedFamilyOrMissingUserNeverBecomesMember() = runBlocking {
        for (roster in listOf(family("other"),family().copy(id="otherFamily"))) {
            val api=Api().apply { this.roster=roster }; val flow=InvitationFlow(api)
            flow.open(url,account); flow.join(); assertEquals(InvitationFlow.Phase.ERROR,flow.state().phase)
            assertNull(flow.state().family)
        }
    }
    @Test fun deadFullAndUnauthorizedAreExplicitNotConnected() = runBlocking {
        for (error in listOf(ContractFailure.Kind.DEAD_INVITE,ContractFailure.Kind.FAMILY_FULL,ContractFailure.Kind.UNAUTHORIZED)) {
            val api=Api().apply { this.error=error };val flow=InvitationFlow(api)
            flow.open(url,account); flow.join(); assertEquals(error,flow.state().failure);assertNull(flow.state().family)
        }
    }
    @Test fun latePreviewAndCancellationCannotResurrectClearedFlow() = runBlocking {
        val api=Api().apply { wait=CompletableDeferred() }; val flow=InvitationFlow(api)
        val job=launch(start=CoroutineStart.UNDISPATCHED) { flow.open(url,account) }
        flow.clear(); api.wait!!.complete(Unit); job.join(); assertEquals(InvitationFlow.Phase.EMPTY,flow.state().phase)
        api.wait=CompletableDeferred(); val cancelled=launch(start=CoroutineStart.UNDISPATCHED) { flow.open(url,account) }
        cancelled.cancelAndJoin(); assertEquals(InvitationFlow.Phase.ERROR,flow.state().phase)
        assertNull(flow.state().family)
    }
    @Test fun signOutDuringMembershipReadCannotResurrectAccess() = runBlocking {
        val api=Api().apply { familyWait=CompletableDeferred() };val flow=InvitationFlow(api)
        flow.open(url,account)
        val job=launch(start=CoroutineStart.UNDISPATCHED) { flow.join() }
        assertEquals(InvitationFlow.Phase.VERIFYING,flow.state().phase)
        flow.clear();api.familyWait!!.complete(Unit);job.join()
        assertEquals(InvitationFlow.Phase.EMPTY,flow.state().phase);assertNull(flow.state().family)
    }
    @Test fun invalidInvitationDoesNotCallNetwork() = runBlocking {
        val api=Api().apply { wait=CompletableDeferred() };val flow=InvitationFlow(api)
        flow.open(url+"?redirect=evil",account); assertEquals(ContractFailure.Kind.INVALID,flow.state().failure)
        assertNull(flow.savedInviteUrl()); assertEquals(0,api.joins)
    }
    @Test fun malformedFamilyRosterNeverGrantsASecondOwnerOrPendingAccess() {
        assertTrue(runCatching { family().copy(members=family().members.map { it.copy(status=MembershipStatus.PENDING) }) }.isFailure)
        assertTrue(runCatching { family().copy(members=family().members+family().members) }.isFailure)
        assertTrue(runCatching { family().copy(owner="stranger") }.isFailure)
        assertTrue(runCatching { JoinReply(JoinStatus.PENDING,"family1",null) }.isFailure)
    }
}
