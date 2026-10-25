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

interface FamilyAuthenticationApi {
    suspend fun authenticate(token: YandexLoginToken, existingEmailSession: FamilySession? = null): FamilySession
    suspend fun refreshSession(credential: String): FamilySession
}

/** Pinned transport. Foreground provisioning is wired behind the default-false flag. */
class HttpsFamilyLinkApi internal constructor(private val http: ContractHttp, private val enabled: () -> Boolean) : FamilyLinkApi, FamilyAuthenticationApi, FamilyManagementApi {
    constructor() : this(ContractHttp(), { FeatureFlags.FAMILY_ROSTER_V2 })
    internal constructor(client: OkHttpClient, enabled: () -> Boolean) : this(ContractHttp(client), enabled)

    private suspend fun request(method: String, path: String, credential: String? = null, body: JsonObject? = null, noContent: Boolean = false): JsonObject {
        if (!enabled()) throw ContractFailure(ContractFailure.Kind.DISABLED)
        return http.request(method, "https://madre-api.kdnfx.space/api/madre/$path", credential, body, noContent = noContent)
    }

    /** Token remains owned by the foreground caller and must close after BOTH provisioning calls. */
    override suspend fun authenticate(token: YandexLoginToken, existingEmailSession: FamilySession?): FamilySession = safe {
        val reply = request("POST", if (existingEmailSession == null) "auth/yandex" else "auth/yandex/link",
            existingEmailSession?.credential, json { addProperty("yandex_token", token.forVerificationRequest()) })
        parseAuth(reply).also {
            require(existingEmailSession == null || it.userId == existingEmailSession.userId)
        }
    }

    /** A saved PB credential must be verified; failure never falls back to creating another account. */
    override suspend fun refreshSession(credential: String): FamilySession = safe {
        if (!enabled()) throw ContractFailure(ContractFailure.Kind.DISABLED)
        parseAuth(http.request("POST", "https://madre-api.kdnfx.space/api/collections/users/auth-refresh",
            credential, json {}))
    }

    private fun parseAuth(reply: JsonObject): FamilySession {
        val record = reply.getAsJsonObject("record")
        val relation = record.get("family")
        val family = when {
            relation == null || relation.isJsonNull -> null
            else -> {
                require(relation.isJsonPrimitive && relation.asJsonPrimitive.isString)
                if (relation.asString.isEmpty()) null else record.string("family")
            }
        }
        // Missing first_name is an honest empty name, NEVER an invented identity.
        val name = record.get("name")
        require(name != null && name.isJsonPrimitive && name.asJsonPrimitive.isString && name.asString.length <= 4096)
        return FamilySession(record.string("id"), name.asString, family, reply.string("token"))
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
        parseRoster(request("GET", "family", session.credential))
    }
    private fun parseRoster(body: JsonObject): FamilyRoster {
        fun members(key: String): List<FamilyMember> = body.getAsJsonArray(key).map { value ->
            val member = value.asJsonObject
            FamilyMember(member.string("id"), member.string("name"), FamilyRole.valueOf(member.string("role").also {
                require(it == "owner" || it == "member") }.uppercase()),
                MembershipStatus.valueOf(member.string("status").also { require(it == "joined") }.uppercase()),
                member.string("joined_at").also { utc(it) }, member.integer("bakes_count"))
        }
        return FamilyRoster(body.string("id"), body.string("name"), body.string("owner"), body.boolean("approval_required"),
            members("members"), body.getAsJsonArray("pending").map { value ->
                val item = value.asJsonObject
                require(item.string("status") == "pending")
                FamilyRequest(item.string("id"), item.string("user_id"), item.string("name"), item.string("requested_at").also { utc(it) })
            })
    }
    private fun segment(id: String): String = id.also { require(it.matches(Regex("[A-Za-z0-9_-]{1,64}"))) }
    private fun label(value: String): String = value.also { require(it.isNotBlank() && it.length <= 120) }
    override suspend fun create(session: FamilySession, name: String): FamilyRoster = safe {
        parseRoster(request("POST", "family/create", session.credential, json { addProperty("name", label(name)) }))
    }
    override suspend fun update(session: FamilySession, name: String?, approvalRequired: Boolean?): FamilyRoster = safe {
        require(name != null || approvalRequired != null)
        parseRoster(request("PATCH", "family", session.credential, json {
            name?.let { addProperty("name", label(it)) }; approvalRequired?.let { addProperty("approval_required", it) }
        }))
    }
    override suspend fun invite(session: FamilySession, rotate: Boolean): ActiveFamilyInvite = safe {
        val body = request(if (rotate) "POST" else "GET", "family/invite", session.credential, if (rotate) json {} else null)
        val invitation = requireNotNull(FamilyInvitation.parse(body.string("url")))
        val expiry = body.string("expires_at").also { utc(it) }
        val seats = if (rotate) body.integer("seats_left").also { require(it <= 7) }.toInt() else null
        ActiveFamilyInvite(invitation, expiry, seats)
    }
    override suspend fun revokeInvite(session: FamilySession) = safe {
        request("DELETE", "family/invite", session.credential, noContent = true); Unit
    }
    override suspend fun renameMember(session: FamilySession, memberId: String, name: String): FamilyRoster = safe {
        parseRoster(request("PATCH", "family/members/${segment(memberId)}", session.credential,
            json { addProperty("display_name", label(name)) }))
    }
    override suspend fun remove(session: FamilySession, memberId: String, rotate: Boolean): RemovedFamilyMember = safe {
        val body = request("DELETE", "family/members/${segment(memberId)}?rotate_invite=${if (rotate) 1 else 0}", session.credential)
        RemovedFamilyMember(body.string("restore_token"), body.string("restore_until").also { utc(it) }, body.integer("removal_revision"))
    }
    override suspend fun restoreMember(session: FamilySession, memberId: String, receipt: RemovedFamilyMember): FamilyRoster = safe {
        parseRoster(request("POST", "family/members/${segment(memberId)}/restore", session.credential,
            json { addProperty("restore_token", receipt.restoreToken) }))
    }
    override suspend fun decide(session: FamilySession, requestId: String, accept: Boolean): FamilyRoster = safe {
        parseRoster(request("POST", "family/requests/${segment(requestId)}", session.credential, json { addProperty("accept", accept) }))
    }
    override suspend fun transfer(session: FamilySession, memberId: String): FamilyRoster = safe {
        parseRoster(request("POST", "family/owner", session.credential, json { addProperty("member_id", segment(memberId)) }))
    }
    override suspend fun leave(session: FamilySession, newOwnerId: String?) = safe {
        val body = request("POST", "family/leave", session.credential, json { newOwnerId?.let { addProperty("new_owner_id", segment(it)) } })
        require(body.boolean("ok")); Unit
    }
    override suspend fun delete(session: FamilySession) = safe {
        request("DELETE", "family", session.credential, noContent = true); Unit
    }
    private fun parseJoin(body: JsonObject): JoinReply = JoinReply(JoinStatus.valueOf(body.string("status").also { require(it in listOf("joined", "pending", "declined")) }.uppercase()),
        body.string("family_id"), body.get("request_id").let { if (it == null || it.isJsonNull) null else body.string("request_id") })
}
