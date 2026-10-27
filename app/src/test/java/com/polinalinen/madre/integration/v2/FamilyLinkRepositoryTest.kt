package com.polinalinen.madre.integration.v2

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Genuine production serializer/repository/controller, synthetic HTTP fixture, NOT PB integration. */
class FamilyLinkRepositoryTest {
    @Test fun ownerMutationsReadBackAndPreserveLegacyLaneAndPersonalBoundary() = runBlocking {
        FamilyWireFixture().use { f ->
            val r = f.repository
            r.refresh(); assertTrue(r.state.value.owner)
            r.renameFamily("Наша семья"); assertEquals("Наша семья", r.state.value.family!!.name)
            r.approval(true); assertTrue(r.state.value.family!!.approvalRequired)
            r.renameMember("member2", "Галя"); assertEquals("Галя", r.state.value.family!!.members.single { it.id == "member2" }.name)
            r.loadInvite(); assertNotNull(r.state.value.invite)
            r.revokeInvite(); assertNull(r.state.value.invite)
            r.loadInvite(); assertNotNull(r.state.value.invite)
            r.remove("member2", true); assertEquals(1, r.state.value.family!!.members.size); assertNotNull(r.state.value.undoName)
            r.restoreRemoved(); assertEquals(2, r.state.value.family!!.members.size); assertNull(r.state.value.undoName)
            r.decide("request1", true); assertTrue(r.state.value.family!!.members.any { it.id == "applicant3" })
            r.transfer("member2"); assertEquals("member2", r.state.value.family!!.owner); assertFalse(r.state.value.owner)
            assertTrue(f.legacy.calls.isEmpty())
            assertTrue(f.calls.any { it == "DELETE" to "/api/madre/family/members/member2?rotate_invite=1" })
            assertTrue(f.bodies.contains("""{"display_name":"Галя"}"""))
            assertFalse(r.state.value.toString().contains("SYNTHETIC_PB"))
            assertFalse(r.state.value.toString().contains("SYNTHETIC_RESTORE"))
        }
    }
    @Test fun memberCanEditSelfShareAndLeaveButCannotRotateRemoveApproveOrRenameFamily() = runBlocking {
        FamilyWireFixture().use { f ->
            f.userId = "member2"; f.requests.clear()
            val r = f.repository; r.refresh(); assertFalse(r.state.value.owner)
            r.renameMember("member2", "Галя"); assertNull(r.state.value.failure)
            r.loadInvite(); assertNotNull(r.state.value.invite)
            for (action in listOf<suspend () -> Unit>({ r.loadInvite(true) }, { r.remove("owner1", true) }, { r.renameFamily("Чужое") }, { r.approval(true) }, { r.transfer("member2") })) {
                val writes = f.calls.count { it.first in listOf("DELETE", "PATCH", "POST") && !it.second.endsWith("auth-refresh") }
                action(); assertNull(r.state.value.family); assertNotNull(r.state.value.failure)
                assertEquals(writes, f.calls.count { it.first in listOf("DELETE", "PATCH", "POST") && !it.second.endsWith("auth-refresh") })
            }
            r.refresh(); r.leave(); assertNull(r.state.value.family); assertNull(f.familyId); assertNotNull(f.tokens.read())
        }
    }
    @Test fun ownerLeaveRequiresExplicitTargetAndSoloDeleteIsNotLegacyLeave() = runBlocking {
        FamilyWireFixture().use { f ->
            f.repository.refresh(); f.repository.leave(); assertNotNull(f.repository.state.value.failure); assertEquals("family1", f.familyId)
            f.repository.refresh(); f.repository.leave("member2"); assertNull(f.familyId); assertEquals("member2", f.owner)
            assertTrue(f.bodies.contains("""{"new_owner_id":"member2"}"""))
        }
        FamilyWireFixture().use { f ->
            f.members.remove("member2"); f.requests.clear(); f.repository.refresh(); f.repository.delete()
            assertNull(f.familyId); assertFalse(f.inviteAlive); assertNull(f.repository.state.value.failure)
            assertTrue(f.calls.any { it == "DELETE" to "/api/madre/family" })
        }
    }
    @Test fun undoExpiresAtEightSecondsAndNeverSurvivesClearOrChangesOwner() = runBlocking {
        FamilyWireFixture().use { f ->
            f.repository.refresh(); f.repository.remove("member2", false)
            f.now += 8_000
            f.repository.restoreRemoved(); assertNotNull(f.repository.state.value.failure); assertFalse(f.members.containsKey("member2"))
            assertTrue(f.calls.none { it.second.endsWith("/restore") })
            f.repository.clear(); assertNull(f.repository.state.value.undoName)
        }
    }
    @Test fun refreshedOwnershipChangeClearsUndoInsteadOfOfferingAFormerOwnerRestore() = runBlocking {
        FamilyWireFixture().use { f ->
            f.repository.refresh(); f.repository.remove("member2", false)
            assertNotNull(f.repository.state.value.undoName)
            f.members["member3"] = "Полина"; f.owner = "member3"
            f.repository.refresh()
            assertFalse(f.repository.state.value.owner); assertNull(f.repository.state.value.undoName)
            assertEquals(0L, f.repository.undoRemaining())
        }
    }
    @Test fun networkFailureAfterMutationDoesNotLeaveOldAccessOrInventUndoSuccess() = runBlocking {
        FamilyWireFixture().use { f ->
            f.repository.refresh(); f.interceptor = { request -> if (request.method == "DELETE") f.errorPath = "/api/madre/family" }
            f.repository.remove("member2", true)
            assertNull(f.repository.state.value.family); assertNull(f.repository.state.value.invite)
            assertNotNull(f.repository.state.value.failure); assertNull(f.repository.state.value.undoName)
            assertFalse(f.repository.state.value.toString().contains("UPSTREAM_SECRET"))
        }
    }
    @Test fun logoutDuringActualSerializedMutationDiscardsLateResponseAndCredential() = runBlocking {
        FamilyWireFixture().use { f ->
            f.repository.refresh()
            val started = CountDownLatch(1); val release = CountDownLatch(1)
            f.interceptor = { request -> if (request.method == "PATCH") { started.countDown(); check(release.await(10, TimeUnit.SECONDS)) } }
            val job = async(Dispatchers.Default) { runCatching { f.repository.renameFamily("Поздний ответ") } }
            assertTrue(withContext(Dispatchers.IO) { started.await(10, TimeUnit.SECONDS) })
            f.accounts.signOut(); f.repository.clear(); release.countDown(); job.await()
            assertNull(f.tokens.read()); assertNull(f.repository.state.value.family); assertFalse(f.repository.state.value.signedIn)
        }
    }
    @Test fun concurrentMutationsAreSerializedInOneRepositoryLane() = runBlocking {
        FamilyWireFixture().use { f ->
            f.repository.refresh()
            coroutineScope { launch { f.repository.renameFamily("Одно") }; launch { f.repository.approval(true) } }
            assertEquals("Одно", f.repository.state.value.family!!.name); assertTrue(f.repository.state.value.family!!.approvalRequired)
        }
    }
    @Test fun concurrentAccountRevisionChangeCannotLeaveAnInfiniteBusyStateOrOldAuthority() = runBlocking {
        FamilyWireFixture().use { f ->
            f.repository.refresh()
            val started = CountDownLatch(1); val release = CountDownLatch(1)
            f.interceptor = { request -> if (request.method == "PATCH") { started.countDown(); check(release.await(10, TimeUnit.SECONDS)) } }
            val job = async(Dispatchers.Default) { runCatching { f.repository.renameFamily("Другой ответ") } }
            assertTrue(withContext(Dispatchers.IO) { started.await(10, TimeUnit.SECONDS) })
            f.accounts.clearInviteCode(); release.countDown(); job.await()
            assertFalse(f.repository.state.value.busy); assertNull(f.repository.state.value.family)
            assertEquals(ContractFailure.Kind.UNAUTHORIZED, f.repository.state.value.failure)
        }
    }
    @Test fun cancellationAfterRequestLeavesNoUnverifiedEmptyFamilyOrOfflineAuthority() = runBlocking {
        FamilyWireFixture().use { f ->
            f.repository.refresh()
            val started = CountDownLatch(1); val release = CountDownLatch(1)
            f.interceptor = { request -> if (request.method == "PATCH") { started.countDown(); check(release.await(10, TimeUnit.SECONDS)) } }
            val job = launch(Dispatchers.Default) { f.repository.renameFamily("Отменённое действие") }
            assertTrue(withContext(Dispatchers.IO) { started.await(10, TimeUnit.SECONDS) })
            job.cancelAndJoin(); release.countDown()
            assertNull(f.repository.state.value.family); assertNull(f.repository.state.value.invite)
            assertEquals(ContractFailure.Kind.NETWORK, f.repository.state.value.failure)
            assertFalse(f.repository.state.value.busy); assertNotNull(f.tokens.read())
        }
    }
    @Test fun mutationAckIsNotSuccessWhenExactTargetReadbackDisagrees() = runBlocking {
        FamilyWireFixture().use { f ->
            f.repository.refresh()
            f.interceptor = { request ->
                if (request.method == "GET" && request.url.encodedPath == "/api/madre/family" && f.calls.any { it.first == "PATCH" }) f.name = "Старое имя"
            }
            f.repository.renameFamily("Новое имя")
            assertNull(f.repository.state.value.family); assertNotNull(f.repository.state.value.failure)
        }
    }
    @Test fun migrationCardDoesNotTreatAnExistingEmailCredentialAsYandexAccess() = runBlocking {
        FamilyWireFixture().use { f ->
            f.tokens.verified = false; f.repository.refresh()
            assertTrue(f.repository.state.value.migration); assertFalse(f.repository.state.value.signedIn); assertTrue(f.calls.isEmpty())
            assertNull(f.repository.invitationSession())
        }
    }
    @Test fun createAndDeclineUseCorrectTypedBodiesAndRealReadback() = runBlocking {
        FamilyWireFixture().use { f ->
            f.familyId = null; f.repository.create("Семья"); assertEquals("Семья", f.repository.state.value.family!!.name)
            f.repository.decide("request1", false); assertTrue(f.repository.state.value.family!!.pending.isEmpty())
            assertTrue(f.bodies.contains("""{"accept":false}"""))
        }
    }
    @Test fun removalIsDiscoveredByAuthRefreshWithoutDeletingPersonalOrServerAccount() = runBlocking {
        FamilyWireFixture().use { f ->
            f.repository.refresh(); f.familyId = null; f.repository.refresh()
            assertNull(f.repository.state.value.family); assertEquals("Каменевы", f.repository.state.value.removedFamilyName)
            assertNotNull(f.tokens.read()); assertTrue(f.legacy.calls.isEmpty())
        }
    }
}
