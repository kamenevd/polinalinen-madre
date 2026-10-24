package com.polinalinen.madre.account.yandex

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class YandexAccountControllerTest {
    private val profile=VerifiedYandexProfile("synthetic_uid_hash","fixture-login","Синтетическое имя",null,true,1000L)
    private fun token()=YandexLoginToken.takeSdkResult("SYNTHETIC_TEST_ONLY_BEARER")

    private class Storage(var snapshot:YandexAccountSnapshot=YandexAccountSnapshot()):YandexAccountStorage {
        var failMarker=false
        override suspend fun read()=snapshot
        override suspend fun markOfferSeen(){snapshot=snapshot.copy(offerSeen=true)}
        override suspend fun storeVerified(profile:VerifiedYandexProfile){snapshot=snapshot.copy(profile=profile,foregroundRetryRequired=false,unbindPending=false)}
        override suspend fun requireForegroundRetry(){if(failMarker)throw IOException("synthetic-private-detail");snapshot=snapshot.copy(foregroundRetryRequired=true)}
        override suspend fun clearAccount(unbindPending:Boolean){snapshot=snapshot.copy(profile=null,foregroundRetryRequired=false,unbindPending=unbindPending)}
        override suspend fun acknowledgeUnbind(){snapshot=snapshot.copy(unbindPending=false)}
    }
    private class Api(val profile:VerifiedYandexProfile):YandexAccountApi {
        val events=mutableListOf<String>()
        var binding:suspend(YandexLoginToken)->VerifiedYandexProfile={profile}
        var unbinding:suspend()->Unit={}
        override suspend fun bind(token:YandexLoginToken):VerifiedYandexProfile {
            assertFalse(token.isClosed);events+="bind-start"
            return binding(token).also{events+="bind-end"}
        }
        override suspend fun unbind(){events+="unbind";unbinding()}
    }

    @Test fun cancellationAndSixtySecondTimeoutAreSilentAndDoNotEnrollAnything()=runTest {
        val storage=Storage();val api=Api(profile);val c=YandexAccountController(backgroundScope,storage,api)
        val first=requireNotNull(c.beginSignIn())
        c.cancelSignIn(first);assertEquals(YandexAccountState.SignedOut,c.state.value)
        c.beginSignIn();advanceTimeBy(60_000);runCurrent()
        assertEquals(YandexAccountState.SignedOut,c.state.value)
        assertTrue(api.events.isEmpty());assertFalse(storage.snapshot.foregroundRetryRequired)
        val failed=requireNotNull(c.beginSignIn());c.failSignIn(failed,YandexAccountError.YANDEX)
        assertEquals(YandexAccountState.Error(YandexAccountError.YANDEX),c.state.value)
        val retried=requireNotNull(c.beginSignIn());c.cancelSignIn(retried)
        assertEquals(YandexAccountState.SignedOut,c.state.value)
        c.close()
    }

    @Test fun freshSdkErrorDisconnectIsLocalAndColdRestartCanSignInWithoutInventingRemoteAck()=runTest {
        val storage=Storage();val api=Api(profile)
        api.unbinding={error("Never-bound installation must not call remote unbind")}
        val c=YandexAccountController(backgroundScope,storage,api)
        val attempt=requireNotNull(c.beginSignIn());c.failSignIn(attempt,YandexAccountError.YANDEX)
        c.signOut()
        assertEquals(YandexAccountState.SignedOut,c.state.value)
        assertFalse(storage.snapshot.unbindPending);assertTrue(api.events.isEmpty())
        assertNotNull(c.beginSignIn());c.close()
        val cold=YandexAccountController(backgroundScope,storage,api);runCurrent()
        assertFalse(cold.pendingUnbind.value);assertNotNull(cold.beginSignIn())
        assertTrue(api.events.isEmpty());cold.close()
    }

    @Test fun browserRetryFencesLateNativeTokenAndOnlyServerResponseSignsIn()=runTest {
        val storage=Storage();val api=Api(profile);val c=YandexAccountController(backgroundScope,storage,api)
        val old=requireNotNull(c.beginSignIn());val current=requireNotNull(c.beginSignIn(browser=true))
        val stale=token();c.submitSdkToken(old,stale).join()
        assertTrue(stale.isClosed);assertTrue(api.events.isEmpty())
        assertEquals(YandexAccountState.SigningIn(current,true),c.state.value)
        val accepted=token();c.submitSdkToken(current,accepted).join()
        assertTrue(accepted.isClosed);assertEquals(YandexAccountState.SignedIn(profile),c.state.value)
        assertEquals(profile,storage.snapshot.profile);assertFalse(storage.snapshot.foregroundRetryRequired)
        val duplicate=token();c.submitSdkToken(current,duplicate).join()
        assertTrue(duplicate.isClosed);assertEquals(listOf("bind-start","bind-end"),api.events)
        assertFalse(c.state.value.toString().contains("SYNTHETIC_TEST_ONLY_BEARER"))
        c.close()
    }

    @Test fun bindingPersistsOnlyRetryMarkerBeforeRequestAndReleasesBearerAfterVerification()=runTest {
        val storage=Storage();val api=Api(profile);val reply=CompletableDeferred<VerifiedYandexProfile>()
        api.binding={reply.await()}
        val c=YandexAccountController(backgroundScope,storage,api);val attempt=requireNotNull(c.beginSignIn())
        val bearer=token();val task=c.submitSdkToken(attempt,bearer);runCurrent()
        assertEquals(YandexAccountState.Binding(attempt),c.state.value)
        assertTrue(storage.snapshot.foregroundRetryRequired);assertNull(storage.snapshot.profile)
        assertFalse(bearer.isClosed)
        reply.complete(profile);task.join()
        assertTrue(bearer.isClosed);assertEquals(YandexAccountState.SignedIn(profile),c.state.value)
        c.close()
    }

    @Test fun tenSecondTimeoutHasNoBearerQueueAndColdControllerCannotInventSuccess()=runTest {
        val storage=Storage();val api=Api(profile);api.binding={awaitCancellation()}
        val c=YandexAccountController(backgroundScope,storage,api);val attempt=requireNotNull(c.beginSignIn())
        val bearer=token();val task=c.submitSdkToken(attempt,bearer);runCurrent()
        advanceTimeBy(10_000);runCurrent();task.join()
        assertTrue(bearer.isClosed);assertTrue(storage.snapshot.foregroundRetryRequired)
        assertEquals(YandexAccountState.Error(YandexAccountError.SERVER),c.state.value)
        c.close()
        val cold=YandexAccountController(backgroundScope,storage,api);runCurrent()
        assertEquals(YandexAccountState.Error(YandexAccountError.SERVER),cold.state.value)
        assertEquals(listOf("bind-start"),api.events)
        cold.close()
    }

    @Test fun ownerCancellationStillClosesTheBorrowedSdkResult()=runTest {
        val owner=CoroutineScope(SupervisorJob()+StandardTestDispatcher(testScheduler))
        val storage=Storage();val api=Api(profile);api.binding={awaitCancellation()}
        val c=YandexAccountController(owner,storage,api);val attempt=requireNotNull(c.beginSignIn())
        val bearer=token();val task=c.submitSdkToken(attempt,bearer);runCurrent()
        owner.cancel();task.join()
        assertTrue(bearer.isClosed);assertTrue(storage.snapshot.foregroundRetryRequired)
        c.close()
    }

    @Test fun logoutWinsOverALateBindAndServerUnbindFollowsThatRequest()=runTest {
        val storage=Storage(YandexAccountSnapshot(offerSeen=true));val api=Api(profile)
        val reply=CompletableDeferred<VerifiedYandexProfile>();api.binding={reply.await()}
        val c=YandexAccountController(backgroundScope,storage,api);val attempt=requireNotNull(c.beginSignIn())
        val bearer=token();val binding=c.submitSdkToken(attempt,bearer);runCurrent()
        val logout=backgroundScope.launch{c.signOut()};runCurrent()
        assertEquals(YandexAccountState.SignedOut,c.state.value)
        assertNull(storage.snapshot.profile);assertTrue(storage.snapshot.unbindPending)
        assertNull(c.beginSignIn())
        reply.complete(profile);binding.join();logout.join()
        assertTrue(bearer.isClosed);assertEquals(listOf("bind-start","bind-end","unbind"),api.events)
        assertEquals(YandexAccountState.SignedOut,c.state.value)
        assertNull(storage.snapshot.profile);assertTrue(storage.snapshot.offerSeen)
        assertFalse(storage.snapshot.unbindPending)
        c.close()
    }

    @Test fun unavailableUnbindSurvivesColdStartAsPendingNotAsALiveAccount()=runTest {
        val storage=Storage(YandexAccountSnapshot(profile=profile,offerSeen=true));val api=Api(profile)
        api.unbinding={throw IOException("synthetic-private-detail")}
        val c=YandexAccountController(backgroundScope,storage,api);runCurrent();c.signOut()
        assertEquals(YandexAccountState.SignedOut,c.state.value);assertTrue(storage.snapshot.unbindPending)
        c.close()
        val cold=YandexAccountController(backgroundScope,storage,api);runCurrent()
        assertNull(cold.beginSignIn());assertNull(storage.snapshot.profile)
        api.unbinding={};cold.retryPendingUnbind()
        assertFalse(storage.snapshot.unbindPending);assertTrue(storage.snapshot.offerSeen)
        assertEquals(YandexAccountState.SignedOut,cold.state.value)
        cold.close()
    }

    @Test fun logoutFencesLateSdkBeforePortraitCleanupAndCacheFailureCannotPreventRevoke()=runTest {
        val storage=Storage(YandexAccountSnapshot(profile=profile,offerSeen=true));val api=Api(profile)
        val c=YandexAccountController(backgroundScope,storage,api)
        val attempt=requireNotNull(c.beginSignIn());c.failSignIn(attempt,YandexAccountError.YANDEX)
        val late=token();var cleanupCalled=false
        c.signOut { cached->
            cleanupCalled=true
            assertEquals(profile,cached)
            assertEquals(YandexAccountState.SignedOut,c.state.value)
            assertNull(storage.snapshot.profile);assertTrue(storage.snapshot.unbindPending)
            c.submitSdkToken(attempt,late)
            throw IOException("synthetic-private-cache-detail")
        }
        runCurrent()
        assertTrue(cleanupCalled);assertTrue(late.isClosed)
        assertEquals(listOf("unbind"),api.events)
        assertFalse(storage.snapshot.unbindPending)
        assertEquals(YandexAccountState.SignedOut,c.state.value)
        assertTrue(storage.snapshot.offerSeen);c.close()
    }

    @Test fun markerWriteFailureStopsBeforeTransportWithoutLeakingItsException()=runTest {
        val storage=Storage().apply{failMarker=true};val api=Api(profile)
        val c=YandexAccountController(backgroundScope,storage,api);val attempt=requireNotNull(c.beginSignIn())
        val bearer=token();c.submitSdkToken(attempt,bearer).join()
        assertTrue(bearer.isClosed);assertTrue(api.events.isEmpty())
        assertEquals(YandexAccountState.Error(YandexAccountError.STORAGE),c.state.value)
        assertFalse(c.state.value.toString().contains("synthetic-private-detail"))
        c.close()
    }

    @Test fun aResultDeliveredAfterOwnerCancellationIsStillReleasedWithoutRunningItsBody()=runTest {
        val owner=CoroutineScope(SupervisorJob()+StandardTestDispatcher(testScheduler))
        val storage=Storage();val api=Api(profile);val c=YandexAccountController(owner,storage,api)
        owner.cancel()
        val bearer=token();c.submitSdkToken(1L,bearer).join()
        assertTrue(bearer.isClosed);assertTrue(api.events.isEmpty())
        assertNull(c.beginSignIn())
        c.close()
    }
}
