package com.polinalinen.madre.integration.v2

import com.polinalinen.madre.account.yandex.YandexLoginToken
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class FamilyAuthTransportRegressionTest {
    private fun client(body: String, inspect: (Request) -> Unit = {}) = ContractHttp.client().newBuilder()
        .addInterceptor { chain ->
            inspect(chain.request())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("Fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

    @Test fun realAuthSerializationAcceptsHonestEmptyNameWithoutInventingAnIdentity() = runBlocking {
        val client = client("""{"token":"SYNTHETIC_PB","record":{"id":"user1","name":"","family":""}}""") { request ->
            assertEquals("https://madre-api.kdnfx.space/api/madre/auth/yandex", request.url.toString())
            assertEquals("2", request.header("X-Madre-Family-Version")); assertNull(request.header("Authorization"))
            assertEquals("POST", request.method)
            assertEquals("""{"yandex_token":"SYNTHETIC_OAUTH"}""", Buffer().also { request.body!!.writeTo(it) }.readUtf8())
        }
        val token = YandexLoginToken.takeSdkResult("SYNTHETIC_OAUTH")
        try {
            val session = HttpsFamilyLinkApi(client) { true }.authenticate(token)
            assertEquals("", session.name); assertEquals("user1", session.userId); assertNull(session.familyId)
            assertFalse(token.isClosed); assertEquals("SYNTHETIC_PB", session.credential)
        } finally { token.close(); client.dispatcher.executorService.shutdown() }
    }

    @Test fun emptyNameDoesNotLoosenTokenIdsFamilyOrJsonStringTypes() = runBlocking {
        val valid = """{"token":"SYNTHETIC_PB","record":{"id":"user1","name":"","family":"family1"}}"""
        val invalid = listOf(
            valid.replace("\"SYNTHETIC_PB\"", "\" \""), valid.replace("\"user1\"", "\"\""),
            valid.replace("\"user1\"", "123"), valid.replace("\"family1\"", "\" \""),
            valid.replace("\"family1\"", "123"), valid.replace("\"family1\"", "{}"),
            valid.replace("\"name\":\"\"", "\"name\":null"), valid.replace("\"name\":\"\"", "\"name\":123"),
            valid.replace("\"name\":\"\",", ""), valid.replace("\"name\":\"\"", "\"name\":\"${"x".repeat(4097)}\""),
        )
        for (body in invalid) {
            val client = client(body); val token = YandexLoginToken.takeSdkResult("SYNTHETIC_OAUTH")
            try {
                val failure = runCatching { HttpsFamilyLinkApi(client) { true }.authenticate(token) }.exceptionOrNull()
                assertTrue(failure is ContractFailure); assertEquals(ContractFailure.Kind.INVALID, (failure as ContractFailure).kind)
                assertNull(failure.cause); assertFalse(failure.toString().contains("SYNTHETIC"))
            } finally { token.close(); client.dispatcher.executorService.shutdown() }
        }
    }

    @Test fun refreshIsPinnedVersionedPbAuthNotOAuthAndDisabledMeansZeroRequests() = runBlocking {
        var calls = 0
        val client = client("""{"token":"REFRESHED","record":{"id":"user1","name":"","family":null}}""") { request ->
            calls++
            assertEquals("https://madre-api.kdnfx.space/api/collections/users/auth-refresh", request.url.toString())
            assertEquals("OLD_PB", request.header("Authorization")); assertEquals("POST", request.method)
            assertEquals("{}", Buffer().also { request.body!!.writeTo(it) }.readUtf8())
        }
        try {
            val denied = runCatching { HttpsFamilyLinkApi(client) { false }.refreshSession("OLD_PB") }.exceptionOrNull()
            assertEquals(ContractFailure.Kind.DISABLED, (denied as ContractFailure).kind); assertEquals(0, calls)
            assertEquals("user1", HttpsFamilyLinkApi(client) { true }.refreshSession("OLD_PB").userId)
            assertEquals(1, calls)
        } finally { client.dispatcher.executorService.shutdown() }
    }
}
