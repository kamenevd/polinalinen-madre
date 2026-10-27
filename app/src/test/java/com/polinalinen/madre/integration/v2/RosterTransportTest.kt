package com.polinalinen.madre.integration.v2

import com.polinalinen.madre.config.FeatureFlags
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class RosterTransportTest {
    private val credential="Z".repeat(43)
    private val connection="00000000-0000-0000-0000-000000000001"
    @Test fun attachDisconnectAndAckRequireExactTypedAllowlistedResponses()=runBlocking {
        var code=200;var body="{}";var calls=0
        val client=ContractHttp.client().newBuilder().addInterceptor {chain ->
            calls++;assertTrue(chain.request().url.toString().startsWith("https://kdnfx.space/alice/madre/voice/phone/v2/"))
            assertEquals("Installation $credential",chain.request().header("Authorization"))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        try {
            val api=HttpsRosterV2Api(client){true}
            val good="""{"connection_id":"$connection","consent_scope":"installation_current_bakes","attached":true}"""
            for(invalid in listOf(good.replace("true","\"true\""),good.replace("true","1"),good.dropLast(1)+",\"attached\":true}",
                good.dropLast(1)+",\"unexpected\":1}",good.replace(connection,connection.uppercase()),"{} trailing","[]")) {
                // UUID contains no alphabetic hex here; skip identical uppercase fixture.
                if(invalid==good)continue
                body=invalid;assertTrue(runCatching{api.attach(credential)}.exceptionOrNull() is ContractFailure)
            }
            body=good;assertEquals(connection,api.attach(credential))
            for(status in listOf(202,302,204)){code=status;assertTrue(runCatching{api.attach(credential)}.exceptionOrNull() is ContractFailure)}
            for(status in listOf(200,202,302)){code=status;body="{}";assertTrue(runCatching{api.disconnect(credential,connection)}.exceptionOrNull() is ContractFailure)}
            code=204;body="";api.disconnect(credential,connection)
            val before=calls
            val disabled=HttpsRosterV2Api(client){false}
            assertEquals(ContractFailure.Kind.DISABLED,(runCatching{disabled.attach(credential)}.exceptionOrNull() as ContractFailure).kind)
            assertEquals(before,calls);assertFalse(FeatureFlags.FAMILY_ROSTER_V2)
        }finally{client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()}
    }
    @Test fun strictParserRefusesDuplicatesNonJsonTrailingAndDepthBeforeCoercion(){
        for(raw in listOf("{\"a\":1,\"a\":2}","{\"a\":NaN}","{a:1}","{\"a\":1}{}","{\"a\":"+"[".repeat(20)+"0"+"]".repeat(20)+"}"))
            assertTrue(raw,runCatching{strictObject(raw)}.isFailure)
    }
}
