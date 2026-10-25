package com.polinalinen.madre.integration.v2

import com.google.gson.JsonParser
import com.polinalinen.madre.account.yandex.YandexLoginToken
import com.polinalinen.madre.config.FeatureFlags
import kotlinx.coroutines.runBlocking
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test

/** Intercepts actual OkHttp requests. These responses are fixtures, not running services. */
class V2TransportTest {
    private val invite=checkNotNull(FamilyInvitation.parse("https://kdnfx.space/madre/f/Abcd0123_-Efgh45"))
    private val account=FamilySession("pb-user","Имя",null,"SYNTHETIC_PB_AUTH")
    private val credential="A".repeat(43)
    private val connection="00000000-0000-0000-0000-000000000001"
    private val key="00000000-0000-0000-0000-000000000002"
    private fun client(code: Int=200, body: String="{}", inspect: (Request)->Unit={}): OkHttpClient =
        ContractHttp.client().newBuilder().addInterceptor { chain ->
            val request=chain.request();inspect(request)
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Synthetic")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
    private fun body(request: Request): String = Buffer().also { checkNotNull(request.body).writeTo(it) }.readUtf8()
    private fun roster()=BakeRoster(connection,4,listOf(RosterBake(key,2,7,"recipe-v1","Хлеб",RosterBakeState.RUNNING,0,2,"Опара",60,portions=1,controlReady=true)))
    private suspend fun failure(expected: ContractFailure.Kind, action: suspend ()->Any?) {
        val error=runCatching { action() }.exceptionOrNull() as? ContractFailure ?: error("Expected safe classified failure")
        assertEquals(expected,error.kind);assertNull(error.cause);assertFalse(error.toString().contains("SECRET"))
    }

    @Test fun defaultFalsePreventsAllNewNetworkRequests()=runBlocking {
        assertFalse(FeatureFlags.FAMILY_ROSTER_V2)
        failure(ContractFailure.Kind.DISABLED) { HttpsFamilyLinkApi().preview(invite) }
        failure(ContractFailure.Kind.DISABLED) { HttpsRosterV2Api().poll(credential,roster()) }
    }
    @Test fun previewPinsOriginAndNeverSendsAnAccountCredential()=runBlocking {
        val http=client(body="""{"family_name":"Семья","inviter_name":"Имя","members_count":1,"bakes_count":2,"initials":["И"],"approval_required":false}""") { request ->
            assertEquals("GET",request.method);assertEquals("madre-api.kdnfx.space",request.url.host)
            assertEquals("/api/madre/invite/Abcd0123_-Efgh45",request.url.encodedPath)
            assertNull(request.header("Authorization"));assertNull(request.url.query)
        }
        try { assertEquals(1,HttpsFamilyLinkApi(http){true}.preview(invite).membersCount) }
        finally { http.dispatcher.executorService.shutdown() }
    }
    @Test fun explicitJoinAndPendingStatusUsePbAuthNotAliceIdentity()=runBlocking {
        val paths=mutableListOf<String>()
        val http=client(body="""{"status":"pending","family_id":"family1","request_id":"req1"}""") { request ->
            assertEquals("SYNTHETIC_PB_AUTH",request.header("Authorization"));assertNull(request.url.query)
            paths.add(request.url.encodedPath)
            if(request.method=="POST") assertEquals("""{"switch_family":false}""",body(request))
        }
        try {
            val api=HttpsFamilyLinkApi(http){true};val pending=api.join(invite,account,false)
            assertEquals(JoinStatus.PENDING,pending.status);api.status(invite,account,"req1")
            assertEquals(listOf("/api/madre/invite/Abcd0123_-Efgh45/join","/api/madre/family/requests/req1/status"),paths)
            failure(ContractFailure.Kind.INVALID) { api.status(invite,account,"../SECRET") }
        } finally { http.dispatcher.executorService.shutdown() }
    }
    @Test fun yandexLinkRetainsExistingPbIdAndOAuthOnlyInRequestBody()=runBlocking {
        val http=client(body="""{"token":"NEW_PB_AUTH","record":{"id":"pb-user","name":"Имя","family":"family1"}}""") { request ->
            assertEquals("/api/madre/auth/yandex/link",request.url.encodedPath)
            assertEquals("SYNTHETIC_PB_AUTH",request.header("Authorization"));assertFalse(request.headers.toString().contains("OAUTH_SECRET"))
            assertEquals("""{"yandex_token":"OAUTH_SECRET"}""",body(request))
        }
        val token=YandexLoginToken.takeSdkResult("OAUTH_SECRET")
        try {
            val result=HttpsFamilyLinkApi(http){true}.authenticate(token,account)
            assertEquals(account.userId,result.userId);assertEquals("family1",result.familyId)
            assertFalse(result.toString().contains("NEW_PB_AUTH"));assertFalse(token.isClosed)
        } finally { token.close();http.dispatcher.executorService.shutdown() }
        assertTrue(token.isClosed)
    }
    @Test fun identityMismatchOrMissingPendingProofIsNotAccepted()=runBlocking {
        for (response in listOf("""{"token":"SECRET","record":{"id":"other","name":"Имя","family":"family1"}}""", "{}")) {
            val http=client(body=response);val token=YandexLoginToken.takeSdkResult("OAUTH_SECRET")
            try { failure(ContractFailure.Kind.INVALID) { HttpsFamilyLinkApi(http){true}.authenticate(token,account) } }
            finally { token.close();http.dispatcher.executorService.shutdown() }
        }
        val http=client(body="""{"status":"pending","family_id":"family1"}""")
        try { failure(ContractFailure.Kind.INVALID) { HttpsFamilyLinkApi(http){true}.join(invite,account,false) } }
        finally { http.dispatcher.executorService.shutdown() }
    }
    @Test fun deadFullConflictAndUnauthorizedErrorsNeverLeakResponseOrUrl()=runBlocking {
        for ((code,error,kind) in listOf(Triple(404,"SECRET",ContractFailure.Kind.DEAD_INVITE),Triple(409,"family_full",ContractFailure.Kind.FAMILY_FULL),
            Triple(409,"already_in_family",ContractFailure.Kind.OTHER_FAMILY),Triple(409,"uid_already_linked",ContractFailure.Kind.CONFLICT),
            Triple(403,"SECRET",ContractFailure.Kind.UNAUTHORIZED),Triple(302,"SECRET",ContractFailure.Kind.SERVER))) {
            val http=client(code,"""{"error":"$error","detail":"SECRET"}""")
            try { failure(kind) { HttpsFamilyLinkApi(http){true}.join(invite,account,false) } }
            finally { http.dispatcher.executorService.shutdown() }
        }
    }
    @Test fun malformedOversizeAndCoercedPreviewCannotBecomeValidData()=runBlocking {
        val valid="""{"family_name":"Семья","inviter_name":"Имя","members_count":1,"bakes_count":2,"initials":["И"],"approval_required":false}"""
        for (response in listOf("{}","SECRET".repeat(12000),valid.replace(":1,",":\"1\","),valid.replace(":1,",":1.5,"),valid.replace(":1,",":9,"),valid.replace(":false",":\"false\""))) {
            val http=client(body=response)
            try { failure(ContractFailure.Kind.INVALID) { HttpsFamilyLinkApi(http){true}.preview(invite) } }
            finally { http.dispatcher.executorService.shutdown() }
        }
    }
    @Test fun rosterSendsVersionedFullAllowlistedArrayAndValidatesEcho()=runBlocking {
        val response="""{"protocol_version":2,"connection_id":"$connection","roster_revision":4,"server_time":1000,"commands":[]}"""
        val http=client(body=response) { request ->
            assertEquals("https://kdnfx.space/alice/madre/voice/phone/v2/poll",request.url.toString())
            assertEquals("Installation $credential",request.header("Authorization"))
            val json=JsonParser.parseString(body(request)).asJsonObject
            assertEquals(setOf("protocol_version","connection_id","roster_revision","bakes"),json.keySet())
            val bake=json.getAsJsonArray("bakes").single().asJsonObject
            assertEquals(setOf("bake_key","bake_number","revision","recipe_revision","recipe_name","state","step_index","step_count","step_title","remaining_seconds","start_at","step_text","next_step_title","next_step_text","quantities","portions","control_ready"),bake.keySet())
            assertEquals(2,bake.get("bake_number").asInt);assertTrue(bake.get("start_at").isJsonNull)
        }
        try { assertTrue(HttpsRosterV2Api(http){true}.poll(credential,roster()).commands.isEmpty()) }
        finally { http.dispatcher.executorService.shutdown() }
        for (bad in listOf(response.replace(":2,",":1,"),response.replace(":4,",":3,"),response.replace(connection,key),response.replace("[]","{}"))) {
            val invalid=client(body=bad)
            try { failure(ContractFailure.Kind.INVALID) { HttpsRosterV2Api(invalid){true}.poll(credential,roster()) } }
            finally { invalid.dispatcher.executorService.shutdown() }
        }
    }
    @Test fun rosterParsesTypedCommandWithoutReflectiveGenericLists()=runBlocking {
        val command="""{"id":"00000000-0000-0000-0000-000000000003","connection_id":"$connection","bake_key":"$key","bake_number":2,"expected_revision":7,"recipe_revision":"recipe-v1","operation":"PAUSE","issued_at":1000,"expires_at":4000,"lease_id":"$connection","expected_step":0,"expected_paused":false}"""
        val response="""{"protocol_version":2,"connection_id":"$connection","roster_revision":4,"server_time":1000,"commands":[$command]}"""
        val http=client(body=response)
        try { val result=HttpsRosterV2Api(http){true}.poll(credential,roster());assertEquals(RosterOperation.PAUSE,result.commands.single().operation) }
        finally { http.dispatcher.executorService.shutdown() }
        for (bad in listOf(response.replace("\"PAUSE\"","\"UNKNOWN\""),response.replace(":2,\"expected",":10,\"expected"),response.replace("[$command]","[$command,$command]"))) {
            val invalid=client(body=bad)
            try { failure(ContractFailure.Kind.INVALID) { HttpsRosterV2Api(invalid){true}.poll(credential,roster()) } }
            finally { invalid.dispatcher.executorService.shutdown() }
        }
    }
    @Test fun authenticatedFamilyUsesTypedPendingRequestsNotFakeMembers()=runBlocking {
        val response="""{"id":"family1","name":"Семья","owner":"pb-user","approval_required":true,"members":[{"id":"pb-user","name":"Имя","role":"owner","status":"joined","joined_at":"2026-10-25T00:00:00Z","bakes_count":2}],"pending":[{"id":"req1","user_id":"candidate","name":"Гость","status":"pending","requested_at":"2026-10-25T00:00:01Z"}]}"""
        val http=client(body=response) { request ->
            assertEquals("/api/madre/family",request.url.encodedPath)
            assertEquals("2",request.header("X-Madre-Family-Version"))
            assertEquals("SYNTHETIC_PB_AUTH",request.header("Authorization"))
        }
        try {
            val roster=HttpsFamilyLinkApi(http){true}.family(account)
            assertEquals(1,roster.members.size);assertEquals("candidate",roster.pending.single().userId)
            assertFalse(roster.members.any { it.id=="candidate" })
        } finally { http.dispatcher.executorService.shutdown() }
        val invalid=client(body=response.replace("\"status\":\"joined\"","\"status\":\"pending\""))
        try { failure(ContractFailure.Kind.INVALID) { HttpsFamilyLinkApi(invalid){true}.family(account) } }
        finally { invalid.dispatcher.executorService.shutdown() }
    }
    @Test fun transportRefusesRedirectCapableClient() {
        assertTrue(runCatching { HttpsFamilyLinkApi(OkHttpClient()){true} }.isFailure)
    }
}
