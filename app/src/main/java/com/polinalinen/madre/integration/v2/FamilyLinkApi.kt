package com.polinalinen.madre.integration.v2

import com.google.gson.JsonObject
import com.polinalinen.madre.config.FeatureFlags
import com.polinalinen.madre.account.yandex.YandexLoginToken
import okhttp3.OkHttpClient

/** Only this exact canonical spelling is accepted; no decode/normalise/clipboard guessing. */
class FamilyInvitation private constructor(internal val token: String) {
    fun savedInviteUrl(): String = "https://kdnfx.space/madre/f/$token"
    override fun toString(): String = "FamilyInvitation([REDACTED])"
    companion object {
        fun parse(raw: String): FamilyInvitation? =
            Regex("https://kdnfx[.]space/madre/f/([A-Za-z0-9_-]{16})").matchEntire(raw)
                ?.groupValues?.get(1)?.let(::FamilyInvitation)
    }
}

/** PB identity, deliberately not the Alice uid hash or installation id. Never log auth. */
class FamilySession(val userId: String, val name: String, val familyId: String?, internal val credential: String) {
    init { require(userId.isNotBlank() && credential.isNotBlank()) }
    override fun toString(): String = "FamilySession([REDACTED])"
}
data class InvitePreview(val familyName: String, val inviterName: String, val membersCount: Int,
                         val bakesCount: Long, val initials: List<String>, val approvalRequired: Boolean)
enum class FamilyRole { OWNER, MEMBER }
enum class MembershipStatus { JOINED, LEFT, REMOVED, PENDING, DORMANT }
data class FamilyMember(val id: String, val name: String, val role: FamilyRole, val status: MembershipStatus,
                        val joinedAt: String, val bakesCount: Long)
data class FamilyRequest(val id: String, val userId: String, val name: String, val requestedAt: String)
data class FamilyRoster(val id: String, val name: String, val owner: String, val approvalRequired: Boolean,
                        val members: List<FamilyMember>, val pending: List<FamilyRequest>) {
    init {
        require(members.size in 1..8 && members.map { it.id }.distinct().size == members.size)
        require(members.all { it.status == MembershipStatus.JOINED })
        require(members.count { it.role == FamilyRole.OWNER && it.id == owner } == 1)
        require(members.count { it.role == FamilyRole.OWNER } == 1)
        require(pending.map { it.id }.distinct().size == pending.size)
        require(pending.all { it.id.matches(Regex("[A-Za-z0-9_-]{1,64}")) && it.userId.isNotBlank() && it.name.isNotBlank() && it.requestedAt.isNotBlank() })
        require((members.map { it.id } + pending.map { it.userId }).distinct().size == members.size + pending.size)
    }
}
enum class JoinStatus { JOINED, PENDING, DECLINED }
data class JoinReply(val status: JoinStatus, val familyId: String, val requestId: String?) {
    init {
        require(familyId.isNotBlank())
        require(status != JoinStatus.PENDING || requestId != null)
        require(requestId == null || requestId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
    }
}

interface FamilyLinkApi {
    suspend fun preview(invite: FamilyInvitation): InvitePreview
    suspend fun join(invite: FamilyInvitation, session: FamilySession, switchFamily: Boolean): JoinReply
    suspend fun status(invite: FamilyInvitation, session: FamilySession, requestId: String): JoinReply
    suspend fun family(session: FamilySession): FamilyRoster
}

/** Real request builder/parser; not connected to legacy UI until parent/backend acceptance. */
class HttpsFamilyLinkApi internal constructor(private val http: ContractHttp, private val enabled: () -> Boolean) : FamilyLinkApi {
    constructor() : this(ContractHttp(), { FeatureFlags.FAMILY_ROSTER_V2 })
    internal constructor(client: OkHttpClient, enabled: () -> Boolean) : this(ContractHttp(client), enabled)

    private suspend fun request(method: String, path: String, credential: String? = null, body: JsonObject? = null): JsonObject {
        if (!enabled()) throw ContractFailure(ContractFailure.Kind.DISABLED)
        return http.request(method, "https://madre-api.kdnfx.space/api/madre/$path", credential, body)
    }

    /** Token remains owned by the foreground caller and must close after BOTH provisioning calls. */
    suspend fun authenticate(token: YandexLoginToken, existingEmailSession: FamilySession? = null): FamilySession = safe {
        val reply = request("POST", if (existingEmailSession == null) "auth/yandex" else "auth/yandex/link",
            existingEmailSession?.credential, json { addProperty("yandex_token", token.forVerificationRequest()) })
        val record = reply.getAsJsonObject("record")
        val family = record.get("family").let { if (it == null || it.isJsonNull || it.asString.isEmpty()) null else record.string("family") }
        FamilySession(record.string("id"), record.string("name"), family, reply.string("token")).also {
            require(existingEmailSession == null || it.userId == existingEmailSession.userId)
        }
    }

    override suspend fun preview(invite: FamilyInvitation): InvitePreview = safe {
        val body = request("GET", "invite/${invite.token}")
        val count = body.integer("members_count").also { require(it <= 8) }.toInt()
        val initials = body.getAsJsonArray("initials").map { require(it.isJsonPrimitive && it.asJsonPrimitive.isString); it.asString.also { s -> require(s.length in 1..16) } }
        require(initials.size <= count)
        InvitePreview(body.string("family_name"), body.string("inviter_name"), count,
            body.integer("bakes_count"), initials, body.boolean("approval_required"))
    }

    override suspend fun join(invite: FamilyInvitation, session: FamilySession, switchFamily: Boolean): JoinReply = safe {
        parseJoin(request("POST", "invite/${invite.token}/join", session.credential,
            json { addProperty("switch_family", switchFamily) })).also { require(it.status != JoinStatus.DECLINED) }
    }
    override suspend fun status(invite: FamilyInvitation, session: FamilySession, requestId: String): JoinReply = safe {
        require(requestId.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        parseJoin(request("GET", "family/requests/$requestId/status", session.credential))
    }
    override suspend fun family(session: FamilySession): FamilyRoster = safe {
        val body = request("GET", "family", session.credential)
        fun members(key: String): List<FamilyMember> = body.getAsJsonArray(key).map { value ->
            val member = value.asJsonObject
            FamilyMember(member.string("id"), member.string("name"), FamilyRole.valueOf(member.string("role").uppercase()),
                MembershipStatus.valueOf(member.string("status").uppercase()), member.string("joined_at"), member.integer("bakes_count"))
        }
        FamilyRoster(body.string("id"), body.string("name"), body.string("owner"), body.boolean("approval_required"),
            members("members"), body.getAsJsonArray("pending").map { value ->
                val item = value.asJsonObject
                require(item.string("status") == "pending")
                FamilyRequest(item.string("id"), item.string("user_id"), item.string("name"), item.string("requested_at"))
            })
    }
    private fun parseJoin(body: JsonObject): JoinReply = JoinReply(JoinStatus.valueOf(body.string("status").uppercase()),
        body.string("family_id"), body.get("request_id").let { if (it == null || it.isJsonNull) null else body.string("request_id") })
}
