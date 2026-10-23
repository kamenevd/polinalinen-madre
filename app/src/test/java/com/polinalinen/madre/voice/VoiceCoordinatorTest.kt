package com.polinalinen.madre.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonObject
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.account.TokenCipher
import com.polinalinen.madre.data.db.entities.VoiceCommandReceiptEntity
import com.polinalinen.madre.model.BakingSession
import com.polinalinen.madre.viewmodel.fakes.sampleRecipe
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MadreApplication::class)
class VoiceCoordinatorTest {
    private val cipher = object : TokenCipher {
        override fun encrypt(plain: ByteArray) = plain.reversedArray()
        override fun decrypt(sealed: ByteArray) = sealed.reversedArray()
    }
    private val prefs get() = ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("voice_test", Context.MODE_PRIVATE)
    private val bake = BakingSession.start(7, sampleRecipe()).copy(recipeRevision = "digest")
    private val controller = object : VoiceController {
        var applied = 0
        override fun voiceSessions() = listOf(bake)
        override fun voiceRecipeRevision() = "digest"
        override suspend fun applyVoice(command: VoiceCommand, receivedElapsed: Long, authority: () -> VoiceAuthority): VoiceCommandReceiptEntity? {
            applied++; return null
        }
    }
    @Before fun setup() { prefs.edit().clear().commit(); Dispatchers.setMain(UnconfinedTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private fun empty() = JsonObject().apply { addProperty("revoked", true); addProperty("selected", true) }

    @Test fun offlinePollWaitsForExplicitRetryInsteadOfStormingTheEndpoint() = runBlocking {
        val first = CompletableDeferred<Unit>(); val second = CompletableDeferred<Unit>()
        var polls = 0
        val api = object : VoiceApi {
            override suspend fun post(path: String, credential: String, payload: Any): JsonObject {
                if (path == "poll") {
                    polls++
                    if (polls == 1) first.complete(Unit) else second.complete(Unit)
                    throw java.io.IOException("explicit offline fixture")
                }
                return empty()
            }
        }
        val c = VoiceCoordinator(VoiceIdentityStore(prefs, cipher), api, Any())
        c.attach(controller); c.select(bake.bakeKey)
        val job = launch { c.runForeground() }
        try {
            withTimeout(5_000) { first.await() }
            delay(1_200)
            assertEquals(1, polls)
            assertEquals(VoiceStatus.OFFLINE, c.state.value.status)
            c.retryConnection()
            withTimeout(5_000) { second.await() }
            assertEquals(2, polls)
        } finally { job.cancelAndJoin() }
    }
    @Test fun openingScreenDoesNotEnrollSelectOrSendData() = runBlocking {
        var calls = 0
        val api = object : VoiceApi { override suspend fun post(path: String, credential: String, payload: Any): JsonObject { calls++; return empty() } }
        val c = VoiceCoordinator(VoiceIdentityStore(prefs, cipher), api, Any())
        c.attach(controller); c.load()
        assertEquals(0, calls); assertNull(c.state.value.selectedBake); assertEquals(VoiceStatus.UNLINKED, c.state.value.status)
    }
    @Test fun disconnectPersistsAcrossCoordinatorRecreationEvenOffline() = runBlocking {
        val store = VoiceIdentityStore(prefs, cipher)
        val api = object : VoiceApi { override suspend fun post(path: String, credential: String, payload: Any): JsonObject {
            if (path == "disconnect" && deny) throw java.io.IOException("fixture offline")
            return empty()
        }; var deny = false }
        val c = VoiceCoordinator(store, api, Any()); c.attach(controller); c.select(bake.bakeKey)
        assertEquals(bake.bakeKey, c.state.value.selectedBake)
        api.deny = true; c.disconnect()
        assertEquals(VoiceStatus.REVOKE_PENDING, c.state.value.status)
        val restarted = VoiceCoordinator(store, api, Any()); restarted.attach(controller); restarted.load()
        assertEquals(VoiceStatus.REVOKE_PENDING, restarted.state.value.status); assertNull(restarted.state.value.selectedBake)
    }
    @Test fun staleSelectionResponseCannotUndoDisconnect() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val store = VoiceIdentityStore(prefs, cipher)
        val api = object : VoiceApi { override suspend fun post(path: String, credential: String, payload: Any): JsonObject {
            if (path == "select") { entered.complete(Unit); release.await() }
            return empty()
        } }
        val c = VoiceCoordinator(store, api, Any()); c.attach(controller)
        val selecting = launch { c.select(bake.bakeKey) }
        withTimeout(5_000) { entered.await() }
        c.revokeLocally(); release.complete(Unit); selecting.join()
        assertNull(store.read()?.bakeKey); assertNull(c.state.value.selectedBake)
    }
    @Test fun foregroundStopAfterRequestBeforeResponseDoesNotApplyCommandOrShowConnected() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val api = object : VoiceApi { override suspend fun post(path: String, credential: String, payload: Any): JsonObject {
            if (path == "poll") {
                entered.complete(Unit); release.await()
                return JsonObject().apply { addProperty("server_time", System.currentTimeMillis()); addProperty("linked", true); add("command", JsonObject()) }
            }
            return empty()
        } }
        val c = VoiceCoordinator(VoiceIdentityStore(prefs, cipher), api, Any()); c.attach(controller); c.select(bake.bakeKey)
        val poll = launch { c.runForeground() }
        withTimeout(5_000) { entered.await() }
        c.stopForeground(); release.complete(Unit)
        yield(); poll.cancelAndJoin()
        assertEquals(0, controller.applied); assertEquals(VoiceStatus.OFFLINE, c.state.value.status)
    }
    @Test fun unlinkedPollNeverAppliesEvenWellFormedCommand() = runBlocking {
        val polled = CompletableDeferred<Unit>()
        var acked = 0
        val api = object : VoiceApi { override suspend fun post(path: String, credential: String, payload: Any): JsonObject {
            if (path == "ack") acked++
            if (path != "poll") return empty()
            val now = System.currentTimeMillis()
            val command = VoiceCommand(java.util.UUID.randomUUID().toString(), VoiceOperation.PAUSE,
                bake.bakeKey, "digest", java.util.UUID.randomUUID().toString(), java.util.UUID.randomUUID().toString(),
                0, 0, false, now, now + 4_000)
            polled.complete(Unit)
            return JsonObject().apply {
                addProperty("server_time", now); addProperty("linked", false)
                add("command", com.google.gson.Gson().toJsonTree(command))
            }
        } }
        val c = VoiceCoordinator(VoiceIdentityStore(prefs, cipher), api, Any())
        c.attach(controller); c.select(bake.bakeKey)
        val poll = launch { c.runForeground() }
        withTimeout(5_000) { polled.await() }; delay(50)
        // Successful phone API poll is NOT OAuth: linked=false stays unlinked.
        assertEquals(VoiceStatus.WAITING_LINK, c.state.value.status)
        poll.cancelAndJoin()
        assertEquals(0, controller.applied); assertEquals(0, acked)
    }
    @Test fun negativeServerSelectionDoesNotPersistConsent() = runBlocking {
        val store = VoiceIdentityStore(prefs, cipher)
        val api = object : VoiceApi { override suspend fun post(path: String, credential: String, payload: Any) =
            empty().apply { if (path == "select") addProperty("selected", false) }
        }
        val c = VoiceCoordinator(store, api, Any()); c.attach(controller); c.select(bake.bakeKey)
        assertNull(store.read()?.bakeKey); assertEquals(VoiceStatus.ERROR, c.state.value.status)
    }
    @Test fun coldRevocationHasNoSelectionEvenIfPlaintextFlagIsLost() {
        val store = VoiceIdentityStore(prefs, cipher)
        store.write(store.create().copy(bakeKey = bake.bakeKey, generation = "generation"))
        store.revoke()
        prefs.edit().remove("revoked").commit()
        assertNull(VoiceIdentityStore(prefs, cipher).read()?.bakeKey)
        assertTrue(store.read()!!.revokePending)
    }
    @Test fun corruptCredentialIsNotReplacedByAnotherInstallation() {
        prefs.edit().putString("identity", "corrupt").commit()
        val store = VoiceIdentityStore(prefs, cipher)
        assertNull(store.read()); assertTrue(runCatching { store.create() }.isFailure)
    }
}
