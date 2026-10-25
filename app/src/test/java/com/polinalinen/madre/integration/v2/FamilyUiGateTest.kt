package com.polinalinen.madre.integration.v2

import com.polinalinen.madre.account.yandex.YandexLoginToken
import com.polinalinen.madre.config.FeatureFlags
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FamilyUiGateTest {
    @Test fun aliceAccountLaneDoesNotEnableAnyFamilyEndpoint() = runBlocking {
        FamilyWireFixture().use { f ->
            val api = HttpsFamilyLinkApi(ContractHttp(f.client), { false }, { true })
            val token = YandexLoginToken.takeSdkResult("SYNTHETIC_OAUTH")
            val session = try { api.authenticate(token) } finally { token.close() }
            assertEquals("owner1", api.refreshSession(session.credential).userId)
            val before = f.calls.toList()
            val invite = requireNotNull(FamilyInvitation.parse(f.url))
            val removed = RemovedFamilyMember("SYNTHETIC_RESTORE", "2026-10-25T04:10:00Z", 1)
            val actions: List<suspend () -> Any?> = listOf(
                { api.preview(invite) }, { api.join(invite, session, false) }, { api.join(invite, session, true) },
                { api.status(invite, session, "request1") }, { api.family(session) },
                { api.create(session, "Семья") }, { api.update(session, name = "Другая") },
                { api.invite(session) }, { api.invite(session, true) }, { api.revokeInvite(session) },
                { api.renameMember(session, "member2", "Имя") }, { api.remove(session, "member2") },
                { api.restoreMember(session, "member2", removed) }, { api.decide(session, "request1", true) },
                { api.transfer(session, "member2") }, { api.leave(session) }, { api.delete(session) },
            )
            for (action in actions) {
                val failure = runCatching { action() }.exceptionOrNull()
                assertTrue(failure is ContractFailure)
                assertEquals(ContractFailure.Kind.DISABLED, (failure as ContractFailure).kind)
            }
            assertEquals(before, f.calls)
            assertEquals(listOf("/api/madre/auth/yandex", "/api/collections/users/auth-refresh"), before.map { it.second })
        }
    }

    @Test fun defaultTransportStillRejectsFamilyEvenWhenCalledWithoutUi() = runBlocking {
        assertFalse(FeatureFlags.FAMILY_LINK_UI)
        val failure = runCatching { HttpsFamilyLinkApi().preview(requireNotNull(
            FamilyInvitation.parse("https://kdnfx.space/madre/f/ABCDEFGHIJKLMNOP"))) }.exceptionOrNull()
        assertEquals(ContractFailure.Kind.DISABLED, (failure as ContractFailure).kind)
    }
}
