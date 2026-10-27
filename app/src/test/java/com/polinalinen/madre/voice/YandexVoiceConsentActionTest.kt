package com.polinalinen.madre.voice

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.gson.JsonObject
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.account.TokenCipher
import com.polinalinen.madre.account.yandex.YandexVoiceConsentApi
import com.polinalinen.madre.data.db.entities.VoiceCommandReceiptEntity
import com.polinalinen.madre.model.BakingSession
import com.polinalinen.madre.viewmodel.fakes.sampleRecipe
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Actual coordinator + encrypted preferences; HTTP peers and signed-in boundary are synthetic. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = MadreApplication::class)
class YandexVoiceConsentActionTest {
    private val prefs get() = ApplicationProvider.getApplicationContext<Context>()
        .getSharedPreferences("yandex_consent_action_test", Context.MODE_PRIVATE)
    private val cipher = object : TokenCipher {
        override fun encrypt(plain: ByteArray) = plain.reversedArray()
        override fun decrypt(sealed: ByteArray) = sealed.reversedArray()
    }
    private val store get() = VoiceIdentityStore(prefs, cipher)
    private val bake = BakingSession.start(7, sampleRecipe()).copy(recipeRevision = "digest")
    private val controller = object : VoiceController {
        override fun voiceSessions() = listOf(bake)
        override fun voiceRecipeRevision() = "digest"
        override suspend fun applyVoice(command: VoiceCommand, receivedElapsed: Long,
            authority: () -> VoiceAuthority): VoiceCommandReceiptEntity? = error("No voice grant in this fixture")
    }
    private class Peer : VoiceApi {
        val calls = mutableListOf<String>()
        var selected = true
        var beforeSelect: suspend () -> Unit = {}
        override suspend fun post(path: String, credential: String, payload: Any): JsonObject {
            calls += path
            if (path == "select") beforeSelect()
            return JsonObject().apply { addProperty("revoked", true); addProperty("selected", selected) }
        }
    }
    @Before fun setup() { prefs.edit().clear().commit() }

    @Test fun loadSelectReconnectAndColdRestoreNeverCreatePhoneEvidence() = runBlocking {
        var consents = 0
        val peer = Peer()
        val consent = YandexVoiceConsentApi { _, _, _ -> consents++; System.currentTimeMillis() + 300_000 }
        val coordinator = VoiceCoordinator(store, peer, Any(), consent)
        coordinator.attach(controller); coordinator.load(); coordinator.select(bake.bakeKey)
        coordinator.retryConnection(); coordinator.stopForeground()
        val cold = VoiceCoordinator(store, peer, Any(), consent)
        cold.attach(controller); cold.load()
        assertEquals(0, consents)
        assertNull(cold.state.value.yandexConsentExpiresAt)
        assertNotEquals(VoiceStatus.CONNECTED, cold.state.value.status)
    }

    @Test fun deliberateTapUsesExistingCredentialAndConfirmedExactGenerationWithoutEnrolling() = runBlocking {
        val original = store.create()
        val peer = Peer()
        var consents = 0
        val coordinator = VoiceCoordinator(store, peer, Any(), YandexVoiceConsentApi { credential, bakeId, generation ->
            consents++
            assertEquals(original.credential, credential)
            assertEquals(bake.bakeKey, bakeId)
            assertEquals(store.read()!!.generation, generation)
            assertEquals(listOf("disconnect", "select"), peer.calls)
            System.currentTimeMillis() + 300_000
        })
        coordinator.attach(controller)
        coordinator.prepareYandexFromExplicitTap(bake.bakeKey) { true }
        assertEquals(1, consents)
        assertEquals(VoiceStatus.WAITING_LINK, coordinator.state.value.status)
        assertNotNull(coordinator.state.value.yandexConsentExpiresAt)
        assertEquals(setOf("identity", "revoked"), prefs.all.keys)
        val cold = VoiceCoordinator(store, peer, Any())
        cold.attach(controller); cold.load()
        assertNull(cold.state.value.yandexConsentExpiresAt)
    }

    @Test fun missingCredentialOrSignedOutDoesNotEnrollOrSendConsent() = runBlocking {
        val peer = Peer()
        var consents = 0
        val coordinator = VoiceCoordinator(store, peer, Any(), YandexVoiceConsentApi { _, _, _ -> consents++; 1L })
        coordinator.attach(controller)
        coordinator.prepareYandexFromExplicitTap(bake.bakeKey) { false }
        coordinator.prepareYandexFromExplicitTap(bake.bakeKey) { true }
        assertNull(store.read()); assertTrue(peer.calls.isEmpty()); assertEquals(0, consents)
    }

    @Test fun rejectedSelectionNeverCreatesConsent() = runBlocking {
        store.create()
        val peer = Peer().apply { selected = false }
        var consents = 0
        val coordinator = VoiceCoordinator(store, peer, Any(), YandexVoiceConsentApi { _, _, _ -> consents++; 1L })
        coordinator.attach(controller); coordinator.prepareYandexFromExplicitTap(bake.bakeKey) { true }
        assertEquals(0, consents); assertNull(store.read()?.bakeKey)
        assertEquals(VoiceStatus.ERROR, coordinator.state.value.status)
    }

    @Test fun accountChangeDuringSelectionCannotCreateEvidenceOrPersistSelection() = runBlocking {
        store.create()
        var signedIn = true
        var consents = 0
        val peer = Peer().apply { beforeSelect = { signedIn = false } }
        val coordinator = VoiceCoordinator(store, peer, Any(), YandexVoiceConsentApi { _, _, _ -> consents++; 1L })
        coordinator.attach(controller); coordinator.prepareYandexFromExplicitTap(bake.bakeKey) { signedIn }
        assertEquals(0, consents); assertNull(store.read()?.bakeKey)
    }

    @Test fun lateConsentAcknowledgementCannotUndoImmediateLogoutFence() = runBlocking {
        store.create()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val coordinator = VoiceCoordinator(store, Peer(), Any(), YandexVoiceConsentApi { _, _, _ ->
            entered.complete(Unit); release.await(); System.currentTimeMillis() + 300_000
        })
        coordinator.attach(controller)
        val action = launch { coordinator.prepareYandexFromExplicitTap(bake.bakeKey) { true } }
        withTimeout(5_000) { entered.await() }
        coordinator.revokeLocally(); release.complete(Unit); action.join()
        assertNull(coordinator.state.value.yandexConsentExpiresAt)
        assertEquals(VoiceStatus.REVOKE_PENDING, coordinator.state.value.status)
        assertNull(store.read()?.bakeKey)
    }

    @Test fun overlappingTapIsDroppedAndFailureNeedsANewDeliberateAction() = runBlocking {
        store.create()
        val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        var consents = 0
        val coordinator = VoiceCoordinator(store, Peer(), Any(), YandexVoiceConsentApi { _, _, _ ->
            consents++; entered.complete(Unit); release.await(); throw java.io.IOException("Synthetic peer unavailable")
        })
        coordinator.attach(controller)
        val first = launch { coordinator.prepareYandexFromExplicitTap(bake.bakeKey) { true } }
        withTimeout(5_000) { entered.await() }
        coordinator.prepareYandexFromExplicitTap(bake.bakeKey) { true }
        release.complete(Unit); first.join()
        coordinator.retryConnection(); coordinator.load(); coordinator.stopForeground()
        assertEquals(1, consents); assertNull(coordinator.state.value.yandexConsentExpiresAt)
        coordinator.prepareYandexFromExplicitTap(bake.bakeKey) { true }
        assertEquals(2, consents)
        assertNotEquals(VoiceStatus.CONNECTED, coordinator.state.value.status)
    }
}
