package com.polinalinen.madre.integration.v2

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.polinalinen.madre.account.AuthTokenStore
import com.polinalinen.madre.account.FamilyAccountRepository
import com.polinalinen.madre.account.ScriptedFamilyApi
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import java.util.concurrent.CopyOnWriteArrayList

/** Synthetic wire producer ONLY. Not the real PB producer and never a connected/e2e claim. */
internal class FamilyWireFixture : AutoCloseable {
    var familyId: String? = "family1"
    var owner = "owner1"
    var userId = "owner1"
    var name = "Каменевы"
    var approval = false
    var inviteAlive = true
    var linkToken = "ABCDEFGHIJKLMNOP"
    var joinStatus = "joined"
    var statusReply = "pending"
    var joinError: String? = null
    var errorPath: String? = null
    var interceptor: ((Request) -> Unit)? = null
    var malformed: Pair<Int, String>? = null
    val members = linkedMapOf("owner1" to "Дима", "member2" to "Бабушка Галя")
    val requests = linkedMapOf("request1" to "applicant3")
    val calls = CopyOnWriteArrayList<Pair<String, String>>()
    val bodies = CopyOnWriteArrayList<String>()
    val client = ContractHttp.client().newBuilder().addInterceptor { chain ->
        val request = chain.request()
        check(request.url.host == "madre-api.kdnfx.space" && request.url.scheme == "https")
        check(request.header("X-Madre-Family-Version") == "2")
        check(request.header("Authorization")?.startsWith("Bearer ") != true)
        val body = request.body?.let { Buffer().also(it::writeTo).readUtf8() }.orEmpty()
        calls += request.method to request.url.encodedPath + (request.url.encodedQuery?.let { "?$it" } ?: "")
        bodies += body
        interceptor?.invoke(request)
        val (code, text) = malformed ?: if (request.url.encodedPath == errorPath) 503 to "UPSTREAM_SECRET_DO_NOT_EXPOSE" else respond(request, body)
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("Synthetic wire fixture")
            .body(text.toResponseBody("application/json".toMediaType())).build()
    }.build()
    val api = HttpsFamilyLinkApi(client) { true }
    class FixtureTokens : AuthTokenStore {
        var credential: String? = "SYNTHETIC_PB"
        var verified = true
        override fun read() = credential
        override fun isYandexVerified() = credential != null && verified
        override fun write(token: String) { credential = token }
        override fun writeYandex(token: String) { credential = token; verified = true }
        override fun clear() { credential = null; verified = false }
    }
    val tokens = FixtureTokens()
    val legacy = ScriptedFamilyApi()
    val accounts = FamilyAccountRepository(legacy, tokens)
    var now = 1_000L
    val repository = FamilyLinkRepository(accounts, api, api, api) { now }
    val url get() = "https://kdnfx.space/madre/f/$linkToken"
    fun roster(): String = json {
        addProperty("id", "family1"); addProperty("name", name); addProperty("owner", owner); addProperty("approval_required", approval)
        add("members", com.google.gson.JsonArray().apply { members.forEach { (id, label) -> add(json {
            addProperty("id", id); addProperty("name", label); addProperty("role", if (id == owner) "owner" else "member")
            addProperty("status", "joined"); addProperty("joined_at", "2026-10-20T12:00:00Z"); addProperty("bakes_count", 3)
        }) } })
        add("pending", com.google.gson.JsonArray().apply { if (owner == userId) requests.forEach { (id, user) -> add(json {
            addProperty("id", id); addProperty("user_id", user); addProperty("name", "Марина"); addProperty("status", "pending"); addProperty("requested_at", "2026-10-24T12:00:00Z")
        }) } })
    }.toString()
    private fun respond(request: Request, raw: String): Pair<Int, String> {
        val path = request.url.encodedPath
        val method = request.method
        val body = if (raw.isEmpty()) JsonObject() else JsonParser.parseString(raw).asJsonObject
        fun ok(text: String = roster()) = 200 to text
        if (path.endsWith("/auth-refresh")) return ok(json {
            addProperty("token", "SYNTHETIC_PB"); add("record", json { addProperty("id", userId); addProperty("name", members[userId].orEmpty()); addProperty("family", familyId) })
        }.toString())
        if (path.endsWith("/auth/yandex/link") || path.endsWith("/auth/yandex")) return ok(json {
            addProperty("token", "SYNTHETIC_PB"); add("record", json { addProperty("id", userId); addProperty("name", members[userId].orEmpty()); addProperty("family", familyId) })
        }.toString())
        if (path.endsWith("/join")) {
            joinError?.let { return 409 to "{\"error\":\"$it\"}" }
            if (joinStatus == "joined") { familyId = "family1"; members[userId] = "Марина"; requests.clear() }
            return ok("{\"status\":\"$joinStatus\",\"family_id\":\"family1\",\"request_id\":\"request1\"}")
        }
        if (path.endsWith("/status")) {
            if (statusReply == "joined") { familyId = "family1"; members[userId] = "Марина"; requests.clear() }
            return ok("{\"status\":\"$statusReply\",\"family_id\":\"family1\",\"request_id\":\"request1\"}")
        }
        if (path.contains("/invite/") && method == "GET") return ok("{\"family_name\":\"Каменевы\",\"inviter_name\":\"Дима\",\"members_count\":2,\"bakes_count\":6,\"initials\":[\"Д\",\"Г\"],\"approval_required\":$approval}")
        if (path.endsWith("/family/invite")) {
            if (method == "DELETE") { inviteAlive = false; return 204 to "" }
            if (method == "POST") { inviteAlive = true; linkToken = "QRSTUVWXYZabcdef" }
            if (!inviteAlive) return 404 to "{\"error\":\"invite_unavailable\"}"
            return ok("{\"url\":\"$url\",\"expires_at\":\"2026-10-31T12:00:00Z\",\"seats_left\":6}")
        }
        if (path.endsWith("/family/create")) { familyId = "family1"; name = body.string("name"); members.clear(); members[userId] = "Дима"; owner = userId; return ok() }
        if (path.contains("/members/")) {
            val id = path.substringAfter("/members/").substringBefore('/')
            if (path.endsWith("/restore")) { check(body.string("restore_token") == "SYNTHETIC_RESTORE"); members[id] = "Бабушка Галя"; return ok() }
            if (method == "PATCH") { members[id] = body.string("display_name"); return ok() }
            if (method == "DELETE") { members.remove(id); if (request.url.queryParameter("rotate_invite") == "1") linkToken = "QRSTUVWXYZabcdef"
                return ok("{\"restore_token\":\"SYNTHETIC_RESTORE\",\"restore_until\":\"2026-10-25T04:10:00Z\",\"removal_revision\":1}") }
        }
        if (path.contains("/requests/") && method == "POST") {
            val id = path.substringAfterLast('/'); val applicant = requests.remove(id)
            if (body.boolean("accept")) members[checkNotNull(applicant)] = "Марина"
            return ok()
        }
        if (path.endsWith("/family/owner")) { owner = body.string("member_id"); requests.clear(); return ok() }
        if (path.endsWith("/family/leave")) { body.get("new_owner_id")?.let { owner = it.asString }; members.remove(userId); familyId = null; return ok("{\"ok\":true}") }
        if (path.endsWith("/family")) {
            if (method == "DELETE") { familyId = null; inviteAlive = false; return 204 to "" }
            if (method == "PATCH") { body.get("name")?.let { name = it.asString }; body.get("approval_required")?.let { approval = it.asBoolean } }
            return ok()
        }
        error("Unimplemented synthetic route")
    }
    override fun close() { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll() }
}
