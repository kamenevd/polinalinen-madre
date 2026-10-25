package com.polinalinen.madre.integration.v2

import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

class InstallationPairTransportTest {
    private val id="a0057010-4837-46be-adc0-0044e0eb685b"
    @Test fun exactBodyResponseAndNoBooleanNumberStringUuidScopeOrExpiryCoercion()=runBlocking {
        var raw="{}";var calls=0
        val credential="P".repeat(43)
        val client=ContractHttp.client().newBuilder().addInterceptor{chain->
            calls++
            assertEquals("https://kdnfx.space/alice/madre/voice/phone/v2/pair-consent",chain.request().url.toString())
            assertEquals("Installation $credential",chain.request().header("Authorization"))
            val buffer=Buffer();chain.request().body!!.writeTo(buffer)
            val body=strictObject(buffer.readUtf8())
            assertEquals(setOf("protocol_version","request_id","consent_scope","consent"),body.keySet())
            assertEquals(2L,body.integer("protocol_version"));assertTrue(body.boolean("consent"))
            assertEquals(id,body.string("request_id"));assertEquals(PAIR_SCOPE,body.string("consent_scope"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(raw.toResponseBody("application/json".toMediaType())).build()
        }.build()
        try{
            val api=HttpsRosterV2Api(client){true};val request=InstallationPairConsent(id)
            val expiry=System.currentTimeMillis()+100_000
            val good="""{"request_id":"$id","consent_scope":"installation_current_bakes","status":"pending","expires_at":$expiry}"""
            raw=good;assertEquals(PendingPairConsent(id,expiry),api.pairConsent(credential,request))
            for(bad in listOf(good.replace(id,id.uppercase()),good.replace("pending","attached"),good.replace("pending","true"),
                good.replace("installation_current_bakes","bake:read&bake:control"),good.replace(expiry.toString(),"true"),
                good.replace(expiry.toString(),"\"$expiry\""),good.replace(expiry.toString(),"${expiry}.0"),
                good.replace(expiry.toString(),"0"),good.replace(expiry.toString(),"9223372036854775807"),
                good.dropLast(1)+",\"status\":\"pending\"}",good.dropLast(1)+",\"token\":\"x\"}","{} trailing","[]")){
                raw=bad;assertTrue(bad,runCatching{api.pairConsent(credential,request)}.exceptionOrNull() is ContractFailure)
            }
            for(bad in listOf(id.uppercase(),"not-a-uuid","0057010-4837-46be-adc0-0044e0eb685b"))assertTrue(runCatching{InstallationPairConsent(bad)}.isFailure)
            assertTrue(runCatching{InstallationPairConsent(id,consent=false)}.isFailure)
            assertTrue(runCatching{InstallationPairConsent(id,"other")}.isFailure)
            val before=calls
            assertEquals(ContractFailure.Kind.DISABLED,(runCatching{HttpsRosterV2Api(client){false}.pairConsent(credential,request)}.exceptionOrNull() as ContractFailure).kind)
            assertEquals(before,calls)
        }finally{client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()}
    }
    @Test fun cancelUsesExistingInstallationDisconnectAndRequiresExactRevocationAck()=runBlocking {
        var raw="""{"revoked":true}"""
        val client=ContractHttp.client().newBuilder().addInterceptor{chain->
            val request=chain.request()
            assertEquals("https://kdnfx.space/alice/madre/voice/phone/disconnect",request.url.toString())
            assertEquals("Installation ${"P".repeat(43)}",request.header("Authorization"))
            val buffer=Buffer();request.body!!.writeTo(buffer);assertEquals("{}",buffer.readUtf8())
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(raw.toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            val api=HttpsRosterV2Api(client){true};api.cancelPair("P".repeat(43))
            for(bad in listOf("{}","""{"revoked":false}""","""{"revoked":"true"}""",
                """{"revoked":true,"extra":1}""","""{"revoked":true,"revoked":false}""")) {
                raw=bad;assertTrue(runCatching{api.cancelPair("P".repeat(43))}.exceptionOrNull() is ContractFailure)
            }
        }finally{client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()}
    }
    @Test fun malformed409CannotBeInterpretedAsPairRequiredOrAlreadyPaired()=runBlocking {
        var raw="{}"
        val client=ContractHttp.client().newBuilder().addInterceptor{chain->Response.Builder().request(chain.request())
            .protocol(Protocol.HTTP_1_1).code(409).message("fixture").body(raw.toResponseBody("application/json".toMediaType())).build()}.build()
        try {
            val api=HttpsRosterV2Api(client){true}
            for(bad in listOf("""{"error":"pair_required","detail":"SECRET"}""",
                """{"error":"pair_required","error":"already_paired"}""",
                """{"error":"pair_required"} trailing""","""{"error":true}""")) {
                raw=bad
                val failure=runCatching{api.attach("P".repeat(43))}.exceptionOrNull() as ContractFailure
                assertEquals(ContractFailure.Kind.CONFLICT,failure.kind)
                assertFalse(failure.toString().contains("SECRET"));assertNull(failure.cause)
            }
        }finally{client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()}
    }
    @Test fun distinctAttachAndConsent409ErrorsCannotStartWrongFallback()=runBlocking {
        var error="pair_required"
        val client=ContractHttp.client().newBuilder().addInterceptor{chain->Response.Builder().request(chain.request())
            .protocol(Protocol.HTTP_1_1).code(409).message("fixture").body("""{"error":"$error"}""".toResponseBody("application/json".toMediaType())).build()}.build()
        try{
            val api=HttpsRosterV2Api(client){true}
            for((wire,kind)in mapOf("pair_required" to ContractFailure.Kind.PAIR_REQUIRED,"binding_required" to ContractFailure.Kind.BINDING_REQUIRED,
                "binding_changed" to ContractFailure.Kind.BINDING_CHANGED,"challenge_consumed" to ContractFailure.Kind.CHALLENGE_CONSUMED,
                "already_paired" to ContractFailure.Kind.ALREADY_PAIRED,"unknown" to ContractFailure.Kind.CONFLICT)){
                error=wire;assertEquals(kind,(runCatching{api.attach("P".repeat(43))}.exceptionOrNull() as ContractFailure).kind)
            }
        }finally{client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()}
    }
}
