package com.polinalinen.madre.integration.v2

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicReference

class FamilyManagementTransportTest {
    private val session = FamilySession("owner1", "", "family1", "SYNTHETIC_PB")
    @Test fun onlyExactWireStatusContentAndTypesAreAccepted() = runBlocking {
        FamilyWireFixture().use { f ->
            for ((code, body) in listOf(202 to f.roster(), 302 to f.roster(), 204 to "", 200 to "<html>error</html>",
                200 to f.roster().replace("\"approval_required\":false", "\"approval_required\":\"false\""),
                200 to f.roster().replace("\"bakes_count\":3", "\"bakes_count\":-1"),
                200 to f.roster().replace("\"owner\":\"owner1\"", "\"owner\":\"member2\""))) {
                f.malformed = code to body
                val failure = runCatching { f.api.family(session) }.exceptionOrNull()
                assertTrue("must reject wire form $code", failure is ContractFailure)
                assertNull(failure!!.cause)
                assertFalse(failure.toString().contains("SYNTHETIC_PB"))
            }
            for (code in listOf(200, 202, 302)) {
                f.malformed = code to "{}"
                assertTrue(runCatching { f.api.revokeInvite(session) }.exceptionOrNull() is ContractFailure)
            }
            f.malformed = 204 to ""
            f.api.revokeInvite(session)
        }
    }
    @Test fun responseFailuresAreClassifiedWithoutBodyOrCredentialLeak() = runBlocking {
        FamilyWireFixture().use { f ->
            for ((code, expected) in listOf(401 to ContractFailure.Kind.UNAUTHORIZED, 403 to ContractFailure.Kind.UNAUTHORIZED,
                404 to ContractFailure.Kind.DEAD_INVITE, 409 to ContractFailure.Kind.CONFLICT, 426 to ContractFailure.Kind.UNSUPPORTED_PROTOCOL,
                429 to ContractFailure.Kind.RATE_LIMITED, 503 to ContractFailure.Kind.SERVER)) {
                f.malformed = code to "SECRET_UPSTREAM_OAUTH"
                val error = runCatching { f.api.family(session) }.exceptionOrNull() as ContractFailure
                assertEquals(expected, error.kind); assertNull(error.cause); assertFalse(error.toString().contains("SECRET"))
            }
        }
    }
    @Test fun unsafeSegmentsAndNoncanonicalInviteResponsesNeverReachUi() = runBlocking {
        FamilyWireFixture().use { f ->
            for (id in listOf("../owner", "user?x=1", "user/id", "", "%2e%2e")) {
                assertTrue(runCatching { f.api.renameMember(session, id, "Имя") }.exceptionOrNull() is ContractFailure)
            }
            assertTrue(f.calls.isEmpty())
            for (url in listOf("http://kdnfx.space/madre/f/ABCDEFGHIJKLMNOP", "https://kdnfx.space:443/madre/f/ABCDEFGHIJKLMNOP", "https://kdnfx.space/madre/f/ABCDEFGHIJKLMNOP?x=1")) {
                f.malformed = 200 to """{"url":"$url","expires_at":"2026-10-31T12:00:00Z","seats_left":6}"""
                assertTrue(runCatching { f.api.invite(session, true) }.exceptionOrNull() is ContractFailure)
            }
        }
    }
    @Test fun disabledMeansZeroHttpCallsAndDefaultRotationIsExplicit() = runBlocking {
        FamilyWireFixture().use { f ->
            val disabled = HttpsFamilyLinkApi(f.client) { false }
            assertEquals(ContractFailure.Kind.DISABLED, (runCatching { disabled.create(session, "Семья") }.exceptionOrNull() as ContractFailure).kind)
            assertTrue(f.calls.isEmpty())
            f.api.remove(session, "member2")
            assertEquals("DELETE" to "/api/madre/family/members/member2?rotate_invite=1", f.calls.single())
        }
    }
    @Test fun actualLoopbackSocketCarriesProductionJsonAndVersionedPocketBaseAuthorization() = runBlocking {
        val seen = AtomicReference<List<String>>()
        FamilyWireFixture().use { f ->
            val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            server.createContext("/api/madre/family") { exchange ->
                seen.set(listOf(exchange.requestMethod, exchange.requestHeaders.getFirst("Authorization"),
                    exchange.requestHeaders.getFirst("X-Madre-Family-Version"), exchange.requestBody.bufferedReader().readText()))
                val bytes = f.roster().toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, bytes.size.toLong()); exchange.responseBody.use { it.write(bytes) }
            }
            server.start()
            val client = ContractHttp.client().newBuilder().addInterceptor { chain ->
                // Test-only socket destination; assert the real caller's pinned origin BEFORE rewriting.
                assertEquals("https://madre-api.kdnfx.space/api/madre/family", chain.request().url.toString())
                chain.proceed(chain.request().newBuilder().url("http://127.0.0.1:${server.address.port}/api/madre/family").build())
            }.build()
            try {
                val roster = HttpsFamilyLinkApi(client) { true }.update(session, approvalRequired = true)
                assertEquals("family1", roster.id)
                assertEquals(listOf("PATCH", "SYNTHETIC_PB", "2", """{"approval_required":true}"""), seen.get())
            } finally { server.stop(0); client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
        }
    }
}
