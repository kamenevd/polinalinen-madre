package com.polinalinen.madre.account

import com.polinalinen.madre.account.yandex.*
import com.polinalinen.madre.integration.v2.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

/** Synthetic boundaries; real controller, repository, mutex/generation and protected-store port. */
@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundProvisioningTest {
    private val profile = VerifiedYandexProfile("alice-hash", "fixture-login", "Имя", null, true, 1000)
    private fun bearer() = YandexLoginToken.takeSdkResult("SYNTHETIC_RAM_ONLY_OAUTH")
    private class Storage : YandexAccountStorage {
        var snapshot = YandexAccountSnapshot()
        var failMarker = false
        var failFinal = false
        override suspend fun read() = snapshot
        override suspend fun markOfferSeen() { snapshot = snapshot.copy(offerSeen = true) }
        override suspend fun storeVerified(profile: VerifiedYandexProfile) {
            if (failFinal) throw IllegalStateException("PRIVATE_STORAGE_DETAIL")
            snapshot = snapshot.copy(profile = profile, foregroundRetryRequired = false)
        }
        override suspend fun storeAliceBound(profile: VerifiedYandexProfile) {
            snapshot = snapshot.copy(profile = profile, foregroundRetryRequired = true)
        }
        override suspend fun requireForegroundRetry() {
            if (failMarker) throw IllegalStateException("PRIVATE_MARKER_DETAIL")
            snapshot = snapshot.copy(foregroundRetryRequired = true)
        }
        override suspend fun clearAccount(unbindPending: Boolean) {
            snapshot = snapshot.copy(profile = null, foregroundRetryRequired = false, unbindPending = unbindPending)
        }
        override suspend fun acknowledgeUnbind() { snapshot = snapshot.copy(unbindPending = false) }
    }
    private inner class Fixture(scope: CoroutineScope, saved: String? = null, enabled: Boolean = true) {
        val storage = Storage()
        val tokens = InMemoryTokenStore(saved)
        val legacy = ScriptedFamilyApi()
        val repository = FamilyAccountRepository(legacy, tokens)
        val events = mutableListOf<String>()
        var owner: YandexLoginToken? = null
        var binding: suspend () -> Unit = {}
        var authenticating: suspend () -> Unit = {}
        var refreshing: suspend () -> Unit = {}
        var unbinding: suspend () -> Unit = {}
        var reply = FamilySession("u1", "", "f1", "SYNTHETIC_PB_NEW")
        val auth = object : FamilyAuthenticationApi {
            override suspend fun refreshSession(credential: String): FamilySession {
                events += "refresh"; assertEquals(tokens.token, credential); refreshing()
                return FamilySession("u1", "", "f1", "SYNTHETIC_PB_REFRESHED")
            }
            override suspend fun authenticate(token: YandexLoginToken, existingEmailSession: FamilySession?): FamilySession {
                events += if (existingEmailSession == null) "auth" else "link"
                assertSame(owner, token); assertFalse(token.isClosed)
                assertTrue(storage.snapshot.foregroundRetryRequired)
                if (saved != null) assertEquals("u1", existingEmailSession?.userId)
                authenticating()
                return reply
            }
        }
        val alice = object : YandexAccountApi {
            override suspend fun bind(token: YandexLoginToken): VerifiedYandexProfile {
                events += "alice"; owner = token; assertFalse(token.isClosed)
                assertTrue(storage.snapshot.foregroundRetryRequired); binding(); return profile
            }
            override suspend fun unbind() { events += "unbind"; unbinding() }
        }
        val family = object : ForegroundFamilyProvisioning {
            override suspend fun authenticate(token: YandexLoginToken, isCurrent: () -> Boolean) {
                repository.authenticateYandex(token, auth, isCurrent)
            }
            override fun signOut() { events += "local-pb-clear"; repository.signOut() }
        }
        val controller = YandexAccountController(scope, storage, alice, if (enabled) family else null)
        suspend fun submit(): Pair<YandexLoginToken, Job> {
            val attempt = requireNotNull(controller.beginSignIn())
            val token = bearer()
            return token to controller.submitSdkToken(attempt, token)
        }
    }

    @Test fun explicitResultBorrowsOneOwnerSequentiallyAndOnlyThenCompletes() = runTest {
        val f = Fixture(backgroundScope); val gate = CompletableDeferred<Unit>()
        f.authenticating = { gate.await() }
        val (token, job) = f.submit(); runCurrent()
        assertEquals(listOf("alice", "auth"), f.events)
        assertFalse(token.isClosed); assertNull(f.tokens.token)
        val partial = f.controller.provisioning.value
        assertTrue(partial.aliceBound); assertFalse(partial.pocketBaseAuthenticated)
        assertEquals(ForegroundProvisioningState.Phase.AUTHENTICATING_POCKETBASE, partial.phase)
        assertTrue(f.controller.state.value is YandexAccountState.Binding)
        assertTrue(f.storage.snapshot.foregroundRetryRequired)
        assertEquals(profile, f.storage.snapshot.profile)
        gate.complete(Unit); job.join()
        assertTrue(token.isClosed); assertEquals("SYNTHETIC_PB_NEW", f.tokens.token)
        assertEquals("", f.repository.currentAccount()?.displayName)
        assertEquals(ForegroundProvisioningState(ForegroundProvisioningState.Phase.COMPLETE, true, true), f.controller.provisioning.value)
        assertFalse(f.storage.snapshot.foregroundRetryRequired)
        assertTrue(f.legacy.calls.isEmpty()); f.controller.close()
    }

    @Test fun savedLiveEmailCredentialLinksSameRecordAndPreservesFamilyWithoutLeave() = runTest {
        val f = Fixture(backgroundScope, "SYNTHETIC_PB_OLD")
        f.repository.restore()
        val (token, job) = f.submit(); job.join()
        assertTrue(token.isClosed); assertEquals(listOf("alice", "refresh", "link"), f.events)
        val account = requireNotNull(f.repository.currentAccount())
        assertEquals("u1", account.userId); assertEquals("f1", account.familyId)
        assertEquals("Ивановы", account.familyName); assertEquals("u1", account.familyOwnerId)
        assertEquals("SYNTHETIC_PB_NEW", f.tokens.token)
        assertFalse(f.legacy.calls.contains("leave")); f.controller.close()
    }

    @Test fun aliceFailureNeverStartsPbAndCannotClaimPartialSuccess() = runTest {
        val f = Fixture(backgroundScope)
        f.binding = { throw YandexAccountFailure(YandexAccountError.FOREIGN_CLIENT) }
        val (token, job) = f.submit(); job.join()
        assertTrue(token.isClosed); assertEquals(listOf("alice"), f.events)
        assertFalse(f.controller.provisioning.value.aliceBound)
        assertFalse(f.controller.provisioning.value.pocketBaseAuthenticated)
        assertEquals(YandexAccountState.Error(YandexAccountError.FOREIGN_CLIENT), f.controller.state.value)
        assertTrue(f.storage.snapshot.foregroundRetryRequired); f.controller.close()
    }

    @Test fun uidConflictRetainsAliceBindingAndOldPbSessionWithoutMergeOrRollback() = runTest {
        val f = Fixture(backgroundScope, "SYNTHETIC_PB_OLD")
        f.authenticating = { throw ContractFailure(ContractFailure.Kind.CONFLICT) }
        val (token, job) = f.submit(); job.join()
        assertTrue(token.isClosed); assertEquals(listOf("alice", "refresh", "link"), f.events)
        assertEquals("SYNTHETIC_PB_OLD", f.tokens.token)
        assertTrue(f.controller.provisioning.value.aliceBound)
        assertFalse(f.controller.provisioning.value.pocketBaseAuthenticated)
        assertEquals(ContractFailure.Kind.CONFLICT, f.controller.provisioning.value.familyError)
        assertTrue(f.storage.snapshot.foregroundRetryRequired); assertTrue(f.legacy.calls.isEmpty())
        f.controller.close()
    }

    @Test fun expiredOrOfflineStoredPbSessionNeverFallsBackToAnotherAccount() = runTest {
        for (kind in listOf(ContractFailure.Kind.UNAUTHORIZED, ContractFailure.Kind.NETWORK)) {
            val f = Fixture(backgroundScope, "SYNTHETIC_PB_OLD")
            f.refreshing = { throw ContractFailure(kind) }
            val (token, job) = f.submit(); job.join()
            assertTrue(token.isClosed); assertEquals(listOf("alice", "refresh"), f.events)
            assertEquals("SYNTHETIC_PB_OLD", f.tokens.token)
            assertEquals(kind, f.controller.provisioning.value.familyError)
            assertTrue(f.storage.snapshot.foregroundRetryRequired); f.controller.close()
        }
    }

    @Test fun cancellationDuringPbPreservesRetryAndAliceButNeverLeaksOwner() = runTest {
        val f = Fixture(backgroundScope); f.authenticating = { awaitCancellation() }
        val (token, job) = f.submit(); runCurrent(); job.cancelAndJoin()
        assertTrue(token.isClosed); assertNull(f.tokens.token)
        assertTrue(f.controller.provisioning.value.aliceBound)
        assertEquals(ForegroundProvisioningState.Phase.RETRY_REQUIRED, f.controller.provisioning.value.phase)
        assertTrue(f.storage.snapshot.foregroundRetryRequired); f.controller.close()
    }

    @Test fun timeoutDuringPbLeavesOnlyMetadataAndRequiresExplicitRetry() = runTest {
        val f = Fixture(backgroundScope); f.authenticating = { awaitCancellation() }
        val (token, job) = f.submit(); runCurrent()
        advanceTimeBy(YandexAccountController.PROVISION_TIMEOUT_MILLIS); runCurrent(); job.join()
        assertTrue(token.isClosed); assertTrue(f.storage.snapshot.foregroundRetryRequired)
        assertEquals(YandexAccountState.Error(YandexAccountError.SERVER), f.controller.state.value)
        assertEquals(listOf("alice", "auth"), f.events); f.controller.close()
    }

    @Test fun cancellationBeforeLazyBodyStillClosesTokenAndDoesNoRequests() = runTest {
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
        val f = Fixture(scope); val attempt = requireNotNull(f.controller.beginSignIn())
        scope.cancel(); val token = bearer(); f.controller.submitSdkToken(attempt, token).join()
        assertTrue(token.isClosed); assertTrue(f.events.isEmpty()); f.controller.close()
    }

    @Test fun duplicateStaleAndCancelledSdkResultsNeverBecomeSecondProvisioning() = runTest {
        val f = Fixture(backgroundScope)
        val old = requireNotNull(f.controller.beginSignIn())
        val current = requireNotNull(f.controller.beginSignIn(true))
        val stale = bearer(); f.controller.submitSdkToken(old, stale).join()
        assertTrue(stale.isClosed); assertTrue(f.events.isEmpty())
        val token = bearer(); val duplicate = bearer()
        val one = f.controller.submitSdkToken(current, token)
        val two = f.controller.submitSdkToken(current, duplicate)
        one.join(); two.join()
        assertTrue(token.isClosed); assertTrue(duplicate.isClosed)
        assertEquals(listOf("alice", "auth"), f.events); f.controller.close()
    }

    @Test fun logoutDuringLateNonCooperativePbReplyCannotResurrectProtectedSession() = runTest {
        val f = Fixture(backgroundScope, "SYNTHETIC_PB_OLD"); val gate = CompletableDeferred<Unit>()
        f.authenticating = { withContext(NonCancellable) { gate.await() } }
        val (token, binding) = f.submit(); runCurrent()
        val logout = backgroundScope.launch { f.controller.signOut() }; runCurrent()
        assertEquals(YandexAccountState.SignedOut, f.controller.state.value)
        assertNull(f.tokens.token); assertNull(f.repository.currentAccount())
        assertFalse(f.events.contains("unbind")) // ordered AFTER the pending sequence
        gate.complete(Unit); binding.join(); logout.join()
        assertTrue(token.isClosed); assertNull(f.tokens.token)
        assertEquals(listOf("alice", "refresh", "link", "local-pb-clear", "unbind"), f.events)
        assertFalse(f.legacy.calls.contains("leave")); f.controller.close()
    }

    @Test fun logoutWhileAlicePendingPreventsPbRequestEntirely() = runTest {
        val f = Fixture(backgroundScope); val gate = CompletableDeferred<Unit>()
        f.binding = { gate.await() }
        val (token, binding) = f.submit(); runCurrent()
        val logout = backgroundScope.launch { f.controller.signOut() }; runCurrent()
        gate.complete(Unit); binding.join(); logout.join()
        assertTrue(token.isClosed); assertEquals(listOf("alice", "local-pb-clear", "unbind"), f.events)
        assertNull(f.tokens.token); f.controller.close()
    }

    @Test fun repositoryLogoutIndependentlyFencesAnInflightAuthReply() = runTest {
        val f = Fixture(backgroundScope); val gate = CompletableDeferred<Unit>()
        f.authenticating = { gate.await() }
        val (token, job) = f.submit(); runCurrent(); f.repository.signOut()
        gate.complete(Unit); job.join()
        assertTrue(token.isClosed); assertNull(f.tokens.token); assertNull(f.repository.currentAccount())
        assertFalse(f.controller.provisioning.value.pocketBaseAuthenticated); f.controller.close()
    }

    @Test fun metadataFailureBeforeNetworkDoesNotSubmitBearerAnywhere() = runTest {
        val f = Fixture(backgroundScope); f.storage.failMarker = true
        val (token, job) = f.submit(); job.join()
        assertTrue(token.isClosed); assertTrue(f.events.isEmpty())
        assertEquals(YandexAccountState.Error(YandexAccountError.STORAGE), f.controller.state.value)
        f.controller.close()
    }

    @Test fun failureAfterPbCommitReportsBothServerFactsButNotReadyOrFalseRollback() = runTest {
        val f = Fixture(backgroundScope); f.storage.failFinal = true
        val (token, job) = f.submit(); job.join()
        assertTrue(token.isClosed); assertEquals("SYNTHETIC_PB_NEW", f.tokens.token)
        assertTrue(f.controller.provisioning.value.aliceBound)
        assertTrue(f.controller.provisioning.value.pocketBaseAuthenticated)
        assertEquals(ForegroundProvisioningState.Phase.RETRY_REQUIRED, f.controller.provisioning.value.phase)
        assertTrue(f.storage.snapshot.foregroundRetryRequired)
        assertEquals(YandexAccountState.Error(YandexAccountError.STORAGE), f.controller.state.value)
        f.controller.close()
    }

    @Test fun defaultOffRetainsLegacyAliceOnlyAndDoesNotClearIndependentPbAccount() = runTest {
        val f = Fixture(backgroundScope, "SYNTHETIC_PB_OLD", enabled = false)
        val (token, job) = f.submit(); job.join(); f.controller.signOut()
        assertTrue(token.isClosed); assertEquals(listOf("alice", "unbind"), f.events)
        assertEquals("SYNTHETIC_PB_OLD", f.tokens.token)
        assertEquals(ForegroundProvisioningState(), f.controller.provisioning.value); f.controller.close()
    }

    @Test fun changedRecordIdFromAnAlternativeAuthAdapterIsRejectedBeforeStoreWrite() = runTest {
        val f = Fixture(backgroundScope, "SYNTHETIC_PB_OLD")
        f.reply = FamilySession("other-user", "", "other-family", "SYNTHETIC_PB_WRONG")
        val (token, job) = f.submit(); job.join()
        assertTrue(token.isClosed); assertEquals("SYNTHETIC_PB_OLD", f.tokens.token)
        assertFalse(f.controller.provisioning.value.pocketBaseAuthenticated); f.controller.close()
    }

    @Test fun provisioningSerializesWithLegacyAccountRestoreAndLogoutStillWinsBothReplies() = runTest {
        val f = Fixture(backgroundScope, "SYNTHETIC_PB_OLD")
        val gate = CompletableDeferred<Unit>(); f.authenticating = { gate.await() }
        val (token, job) = f.submit(); runCurrent()
        val restoring = backgroundScope.launch { f.repository.restore() }; runCurrent()
        assertTrue(f.legacy.calls.isEmpty()) // restore waits for the same repository mutex
        f.repository.signOut(); gate.complete(Unit); job.join(); restoring.join()
        assertTrue(token.isClosed); assertNull(f.tokens.token)
        assertEquals(FamilyBookState.SignedOut, f.repository.state.value)
        assertTrue(f.legacy.calls.isEmpty()); f.controller.close()
    }

    @Test fun failedPbLocalCleanupDoesNotPreventAliceRevokeOrBecomeFalseSuccess() = runTest {
        val storage = Storage(); storage.snapshot = YandexAccountSnapshot(profile = profile)
        var revoked = false
        val alice = object : YandexAccountApi {
            override suspend fun bind(token: YandexLoginToken) = profile
            override suspend fun unbind() { revoked = true }
        }
        val family = object : ForegroundFamilyProvisioning {
            override suspend fun authenticate(token: YandexLoginToken, isCurrent: () -> Boolean) = Unit
            override fun signOut() { throw IllegalStateException("PRIVATE_CLEANUP_FAILURE") }
        }
        val c = YandexAccountController(backgroundScope, storage, alice, family)
        c.signOut()
        assertTrue(revoked); assertNull(storage.snapshot.profile)
        assertEquals(YandexAccountState.Error(YandexAccountError.STORAGE), c.state.value)
        assertFalse(c.state.value.toString().contains("PRIVATE")); c.close()
    }

    @Test fun coldControllerFromPartialMetadataDoesNoLoginAndOnlyExplicitNewSdkResultRetries() = runTest {
        val f = Fixture(backgroundScope)
        f.authenticating = { throw ContractFailure(ContractFailure.Kind.NETWORK) }
        val (oldToken, first) = f.submit(); first.join(); f.controller.close()
        val cold = YandexAccountController(backgroundScope, f.storage, f.alice, f.family)
        runCurrent(); assertTrue(oldToken.isClosed)
        assertEquals(listOf("alice", "auth"), f.events)
        assertEquals(ForegroundProvisioningState.Phase.RETRY_REQUIRED, cold.provisioning.value.phase)
        assertTrue(cold.provisioning.value.aliceBound); assertFalse(cold.provisioning.value.pocketBaseAuthenticated)
        f.authenticating = {}; val fresh = bearer()
        cold.submitSdkToken(requireNotNull(cold.beginSignIn()), fresh).join()
        assertTrue(fresh.isClosed); assertEquals(listOf("alice", "auth", "alice", "auth"), f.events)
        assertFalse(f.storage.snapshot.foregroundRetryRequired); cold.close()
    }
}
