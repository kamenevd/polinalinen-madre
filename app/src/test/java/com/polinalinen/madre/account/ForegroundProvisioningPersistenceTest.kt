package com.polinalinen.madre.account

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.account.yandex.*
import com.polinalinen.madre.integration.v2.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/** Real serialized requests, real DataStore close/reopen, AES protected PB store and Room.
 * No live HTTP, login, Android Keystore/device claim or fixture result presented as server proof.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = MadreApplication::class)
class ForegroundProvisioningPersistenceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val oauth = "SYNTHETIC_RAM_ONLY_OAUTH"
    private val pb = "SYNTHETIC_PROTECTED_PB"
    private val profileJson = """{"uid_hash":"fixture-hash","login":"fixture-login","display_name":"Имя","avatar_empty":true}"""
    private val pbJson get() = """{"token":"$pb","record":{"id":"u1","name":"","family":"f1"}}"""
    private class AesFixtureCipher : TokenCipher {
        private val key = KeyGenerator.getInstance("AES").apply { init(128) }.generateKey()
        override fun encrypt(plain: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, key); iv + doFinal(plain)
        }
        override fun decrypt(sealed: ByteArray): ByteArray = Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, sealed.copyOfRange(0, 12)))
            doFinal(sealed, 12, sealed.size - 12)
        }
    }
    private fun adapter(repository: FamilyAccountRepository, auth: FamilyAuthenticationApi) = object : ForegroundFamilyProvisioning {
        override suspend fun authenticate(token: YandexLoginToken, isCurrent: () -> Boolean) {
            repository.authenticateYandex(token, auth, isCurrent)
        }
        override fun signOut() { repository.signOut() }
    }
    private fun client(handler: (Request) -> Pair<Int, String>) = ContractHttp.client().newBuilder()
        .addInterceptor { chain ->
            val (code, body) = handler(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("Synthetic")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
    private fun requestBody(request: Request) = Buffer().also { request.body!!.writeTo(it) }.readUtf8()
    private fun owner() = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private suspend fun stop(scope: CoroutineScope) { scope.cancel(); scope.coroutineContext.job.join() }

    @Test fun serializedAliceThenPbAuthPersistsOnlyProtectedPbAndLogoutKeepsAllRoomRows() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MadreApplication>()
        val scope = owner(); val cipher = AesFixtureCipher(); val tokens = SecureTokenStore(app, cipher)
        tokens.clear()
        val legacy = ScriptedFamilyApi(); val repository = FamilyAccountRepository(legacy, tokens)
        val file = File(temporary.root, "auth.preferences_pb")
        val storage = YandexAccountPrefs(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        val http = client { request ->
            paths += request.url.encodedPath
            assertEquals("https", request.url.scheme); assertNull(request.url.query)
            assertFalse(request.headers.toString().contains(oauth)); assertFalse(request.url.toString().contains(oauth))
            when (request.url.encodedPath) {
                "/alice/madre/voice/phone/yandex-bind" -> {
                    assertEquals("kdnfx.space", request.url.host)
                    assertEquals("Installation ${"A".repeat(43)}", request.header("Authorization"))
                    assertEquals("""{"yandex_token":"$oauth"}""", requestBody(request)); 200 to profileJson
                }
                "/api/madre/auth/yandex" -> {
                    assertEquals("madre-api.kdnfx.space", request.url.host); assertNull(request.header("Authorization"))
                    assertEquals("2", request.header("X-Madre-Family-Version"))
                    assertEquals("""{"yandex_token":"$oauth"}""", requestBody(request)); 200 to pbJson
                }
                "/alice/madre/voice/phone/yandex-unbind" -> 204 to ""
                else -> error("Unexpected synthetic route")
            }
        }
        val alice = HttpsYandexAccountApi({ "A".repeat(43) }, http, { 1000 })
        val controller = YandexAccountController(scope, storage, alice, adapter(repository, HttpsFamilyLinkApi(http) { true }))
        try {
            val recipe = app.recipeRepository.getRecipes().first()
            val row = app.bakeHistoryRepository.record(recipe.id, recipe.name, 2, 1_700_000_000_000L)
            suspend fun counts(): Map<String, Long> = withContext(Dispatchers.IO) {
                val db = app.database.openHelper.writableDatabase
                val tables = db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'")
                    .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
                tables.associateWith { name -> db.query("SELECT COUNT(*) FROM \"${name.replace("\"", "\"\"")}\"")
                    .use { q -> q.moveToFirst(); q.getLong(0) } }
            }
            val before = counts()
            val photo = File(app.filesDir, "synthetic-foreground-private.bin").apply { writeBytes(byteArrayOf(1, 2, 3)) }
            val unrelated = app.getSharedPreferences("synthetic-foreground-book", 0)
            unrelated.edit().putString("book", "preserved").commit()
            val token = YandexLoginToken.takeSdkResult(oauth)
            withTimeout(5000) { controller.submitSdkToken(requireNotNull(controller.beginSignIn()), token).join() }
            assertTrue(token.isClosed)
            assertEquals(listOf("/alice/madre/voice/phone/yandex-bind", "/api/madre/auth/yandex"), paths.toList())
            assertEquals("", repository.currentAccount()?.displayName)
            assertEquals(pb, SecureTokenStore(app, cipher).read())
            val rawProtected = app.getSharedPreferences(SecureTokenStore.PREFS, 0).getString(SecureTokenStore.KEY, null)!!
            assertFalse(rawProtected.contains(pb)); assertFalse(rawProtected.contains(oauth))
            assertFalse(file.readBytes().toString(Charsets.UTF_8).contains(oauth))
            assertFalse(file.readBytes().toString(Charsets.UTF_8).contains(pb))
            controller.signOut()
            assertNull(tokens.read()); assertNull(repository.currentAccount())
            assertEquals(before, counts())
            assertTrue(app.bakeHistoryRepository.observeAll().first().any { it.id == row })
            assertArrayEquals(byteArrayOf(1, 2, 3), photo.readBytes())
            assertEquals("preserved", unrelated.getString("book", null))
            assertTrue(legacy.calls.isEmpty()); assertEquals("/alice/madre/voice/phone/yandex-unbind", paths.last())
        } finally { controller.close(); stop(scope); http.dispatcher.executorService.shutdown(); tokens.clear() }
    }

    @Test fun partialLinkConflictSurvivesActualDataStoreReopenWithoutAutomaticSdkOrNetwork() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<MadreApplication>()
        var scope = owner(); val cipher = AesFixtureCipher(); val tokens = SecureTokenStore(app, cipher)
        tokens.clear(); tokens.write("SYNTHETIC_OLD_PB")
        val file = File(temporary.root, "partial.preferences_pb")
        var storage = YandexAccountPrefs(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
        val paths = java.util.Collections.synchronizedList(mutableListOf<String>())
        var conflict = true
        val http = client { request ->
            paths += request.url.encodedPath
            when (request.url.encodedPath) {
                "/alice/madre/voice/phone/yandex-bind" -> {
                    assertEquals("""{"yandex_token":"$oauth"}""", requestBody(request)); 200 to profileJson
                }
                "/api/collections/users/auth-refresh" -> {
                    assertEquals("SYNTHETIC_OLD_PB", request.header("Authorization"))
                    200 to """{"token":"SYNTHETIC_REFRESHED_PB","record":{"id":"u1","name":"","family":"f1"}}"""
                }
                "/api/madre/auth/yandex/link" -> {
                    assertEquals("SYNTHETIC_REFRESHED_PB", request.header("Authorization"))
                    assertEquals("""{"yandex_token":"$oauth"}""", requestBody(request))
                    if (conflict) 409 to """{"error":"uid_already_linked","detail":"PRIVATE_DETAIL"}""" else 200 to pbJson
                }
                else -> error("No fallback auth, join or leave allowed")
            }
        }
        val alice = HttpsYandexAccountApi({ "A".repeat(43) }, http, { 1000 })
        fun family() = adapter(FamilyAccountRepository(ScriptedFamilyApi(), tokens), HttpsFamilyLinkApi(http) { true })
        var controller = YandexAccountController(scope, storage, alice, family())
        try {
            val token = YandexLoginToken.takeSdkResult(oauth)
            withTimeout(5000) { controller.submitSdkToken(requireNotNull(controller.beginSignIn()), token).join() }
            assertTrue(token.isClosed); assertTrue(controller.provisioning.value.aliceBound)
            assertFalse(controller.provisioning.value.pocketBaseAuthenticated)
            assertEquals(ContractFailure.Kind.CONFLICT, controller.provisioning.value.familyError)
            assertEquals("SYNTHETIC_OLD_PB", tokens.read())
            controller.close(); stop(scope)
            assertFalse(file.readBytes().toString(Charsets.UTF_8).contains(oauth))
            scope = owner()
            storage = YandexAccountPrefs(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
            controller = YandexAccountController(scope, storage, alice, family())
            // beginSignIn only returns a non-secret request id. It NEVER launches SDK/network.
            val attempt = requireNotNull(controller.beginSignIn())
            assertEquals(3, paths.size); assertTrue(storage.read().foregroundRetryRequired)
            assertNotNull(storage.read().profile)
            assertEquals(ForegroundProvisioningState.Phase.RETRY_REQUIRED, controller.provisioning.value.phase)
            conflict = false
            val fresh = YandexLoginToken.takeSdkResult(oauth)
            withTimeout(5000) { controller.submitSdkToken(attempt, fresh).join() }
            assertTrue(fresh.isClosed); assertEquals(6, paths.size)
            assertEquals(pb, tokens.read()); assertFalse(storage.read().foregroundRetryRequired)
            assertEquals(ForegroundProvisioningState.Phase.COMPLETE, controller.provisioning.value.phase)
        } finally { controller.close(); stop(scope); http.dispatcher.executorService.shutdown(); tokens.clear() }
    }

    @Test fun markerWithoutProfileOrOldRestoredProfileNeverStartsProvisioningOnInitialization() = runBlocking {
        for (partial in listOf(false, true)) {
            var scope = owner(); val file = File(temporary.root, "restore-$partial.preferences_pb")
            var storage = YandexAccountPrefs(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
            if (partial) storage.storeVerified(VerifiedYandexProfile("fixture-hash", "fixture-login", "", null, true, 1000))
            else storage.requireForegroundRetry()
            stop(scope); scope = owner()
            storage = YandexAccountPrefs(PreferenceDataStoreFactory.create(scope = scope, produceFile = { file }))
            var calls = 0
            val alice = object : YandexAccountApi {
                override suspend fun bind(token: YandexLoginToken): VerifiedYandexProfile { calls++; error("Not explicit") }
                override suspend fun unbind() { calls++ }
            }
            val family = object : ForegroundFamilyProvisioning {
                override suspend fun authenticate(token: YandexLoginToken, isCurrent: () -> Boolean) { calls++; error("Not explicit") }
                override fun signOut() { calls++ }
            }
            val c = YandexAccountController(scope, storage, alice, family)
            try {
                val attempt = requireNotNull(c.beginSignIn()); c.cancelSignIn(attempt)
                assertEquals(0, calls)
                assertFalse(c.provisioning.value.pocketBaseAuthenticated)
                if (!partial) assertEquals(ForegroundProvisioningState.Phase.RETRY_REQUIRED, c.provisioning.value.phase)
            } finally { c.close(); stop(scope) }
        }
    }
}
