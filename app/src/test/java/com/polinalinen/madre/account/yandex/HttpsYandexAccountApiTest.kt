package com.polinalinen.madre.account.yandex

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

/** All responses here are synthetic interceptor fixtures; no live login/server request. */
class HttpsYandexAccountApiTest {
    private val credential="A".repeat(43)
    private val response="""{"uid_hash":"synthetic_uid_hash","login":"fixture-login","display_name":"Синтетическое имя","avatar_id":"12345/test"}"""

    private fun client(code:Int=200,body:String=response,onRequest:(Request)->Unit={}):OkHttpClient =
        OkHttpClient.Builder().addInterceptor{chain->
            val request=chain.request();onRequest(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Synthetic fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()

    @Test fun bindPinsHttpsOriginAndKeepsBearerOutOfUrlAndHeaders()=runBlocking {
        val token=YandexLoginToken.takeSdkResult("SYNTHETIC_TEST_ONLY_BEARER")
        var inspected=false
        val http=client(onRequest={r->
            assertEquals("https://kdnfx.space/alice/madre/voice/phone/yandex-bind",r.url.toString())
            assertNull(r.url.query);assertEquals("Installation $credential",r.header("Authorization"))
            assertFalse(r.headers.toString().contains("SYNTHETIC_TEST_ONLY_BEARER"))
            val buffer=Buffer();requireNotNull(r.body).writeTo(buffer)
            assertEquals("""{"yandex_token":"SYNTHETIC_TEST_ONLY_BEARER"}""",buffer.readUtf8())
            inspected=true
        })
        try {
            val profile=HttpsYandexAccountApi({credential},http,{1000L}).bind(token)
            assertTrue(inspected);assertEquals("synthetic_uid_hash",profile.uidHash)
            assertEquals(1000L,profile.boundAtMillis)
            assertEquals("Синтетическое имя",profile.displayName)
        } finally {token.close();http.dispatcher.executorService.shutdown()}
    }

    @Test fun foreignClientAndMalformedOrOversizedBodiesAreNeverProfiles()=runBlocking {
        listOf(403 to """{"error":"foreign_client","private":"synthetic-private-detail"}""",
            200 to "{}",200 to "x".repeat(65_537),502 to "synthetic-private-detail").forEach { (code,body)->
            val http=client(code,body);val token=YandexLoginToken.takeSdkResult("SYNTHETIC_TEST_ONLY_BEARER")
            try {
                val result=runCatching{HttpsYandexAccountApi({credential},http,{1000L}).bind(token)}
                val error=result.exceptionOrNull() as? YandexAccountFailure ?: error("Expected classified failure")
                assertEquals(if(code==403)YandexAccountError.FOREIGN_CLIENT else YandexAccountError.SERVER,error.kind)
                assertFalse(error.toString().contains("synthetic-private-detail"))
                assertFalse(error.toString().contains("SYNTHETIC_TEST_ONLY_BEARER"))
            } finally {token.close();http.dispatcher.executorService.shutdown()}
        }
    }

    @Test fun onlyExactNoContentAcknowledgesUnbindAndItCarriesNoLoginBearer()=runBlocking {
        listOf(204,200,202,302).forEach { status->
            val http=client(status,""){r->
                assertEquals("/alice/madre/voice/phone/yandex-unbind",r.url.encodedPath)
                val buffer=Buffer();requireNotNull(r.body).writeTo(buffer);assertEquals("{}",buffer.readUtf8())
            }
            try {
                val result=runCatching{HttpsYandexAccountApi({credential},http,{1000L}).unbind()}
                assertEquals(status==204,result.isSuccess)
            } finally {http.dispatcher.executorService.shutdown()}
        }
    }
}
