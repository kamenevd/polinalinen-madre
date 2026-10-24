package com.polinalinen.madre.account.yandex

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

/** Production serialization/transport; only the external HTTPS peer is a synthetic interceptor. */
class HttpsYandexVoiceConsentApiTest {
    private val credential = "A".repeat(43)
    private val bake = "b39aab93-e27d-43d7-a45d-e9326dc72c10"
    private val generation = "35cab4a9-7652-428b-af04-e85ecfb264dd"

    private fun http(code: Int = 200, body: String = """{"expires_at":301000}""",
        inspect: (Request) -> Unit = {}): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        inspect(request)
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Synthetic peer")
            .body(body.toResponseBody("application/json".toMediaType())).build()
    }.build()

    @Test fun exactPostCarriesOnlyCurrentSelectionAndNeverProvisionsIdentity() = runBlocking {
        var requests = 0
        val client = http { request ->
            requests++
            assertEquals("POST", request.method)
            assertEquals("https://kdnfx.space/alice/madre/voice/phone/yandex-voice-consent", request.url.toString())
            assertEquals("Installation $credential", request.header("Authorization"))
            assertNull(request.header("Cookie")); assertNull(request.url.query)
            val buffer = Buffer(); requireNotNull(request.body).writeTo(buffer)
            val json = Gson().fromJson(buffer.readUtf8(), JsonObject::class.java)
            assertEquals(setOf("bake_id", "generation"), json.keySet())
            assertEquals(bake, json.get("bake_id").asString)
            assertEquals(generation, json.get("generation").asString)
        }
        try {
            val api = HttpsYandexAccountApi({ error("Consent must not provision") }, client, { 1000L },
                { error("Consent must not call unbind provider") })
            assertEquals(301000L, api.prepareVoiceConsent(credential, bake, generation))
            assertEquals(1, requests)
        } finally { client.dispatcher.executorService.shutdown() }
    }

    @Test fun redirectsAcceptedOrUnknownAuthAreNotConsentAndNeverRetried() = runBlocking {
        listOf(202, 204, 302, 400, 401, 409, 503).forEach { status ->
            var requests = 0
            val client = http(status, """{"error":"SYNTHETIC_PRIVATE_DETAIL"}""") { requests++ }
            try {
                val failure = runCatching {
                    HttpsYandexAccountApi({ error("No enrollment") }, client, { 1000L })
                        .prepareVoiceConsent(credential, bake, generation)
                }.exceptionOrNull()
                assertTrue(failure is YandexAccountFailure)
                assertFalse(failure.toString().contains("SYNTHETIC_PRIVATE_DETAIL"))
                assertEquals(1, requests)
            } finally { client.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun malformedExpiredOversizedAndExtraFieldResponsesNeverBecomeConsent() = runBlocking {
        listOf("{}", """{"expires_at":"301000"}""", """{"expires_at":1000}""",
            """{"expires_at":99999999}""", """{"expires_at":300000.5}""",
            """{"expires_at":301000,"scope":"bake:read&bake:control"}""", "x".repeat(65_537)).forEach { body ->
            val client = http(body = body)
            try {
                assertTrue(runCatching { HttpsYandexAccountApi({ credential }, client, { 1000L })
                    .prepareVoiceConsent(credential, bake, generation) }.isFailure)
            } finally { client.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun malformedIdentifiersFailWithoutARequestOrCredentialReplacement() = runBlocking {
        var requests = 0
        val client = http { requests++ }
        try {
            val api = HttpsYandexAccountApi({ error("No enrollment") }, client, { 1000L })
            listOf(Triple("short", bake, generation), Triple(credential, "not-a-uuid", generation),
                Triple(credential, bake, "not-a-uuid")).forEach { (auth, id, gen) ->
                assertTrue(runCatching { api.prepareVoiceConsent(auth, id, gen) }.isFailure)
            }
            assertEquals(0, requests)
        } finally { client.dispatcher.executorService.shutdown() }
    }
}
