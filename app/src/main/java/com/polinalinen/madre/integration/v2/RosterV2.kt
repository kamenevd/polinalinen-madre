package com.polinalinen.madre.integration.v2

import com.google.gson.JsonArray
import com.polinalinen.madre.config.FeatureFlags
import okhttp3.OkHttpClient

/** Immutable current-only allowlist. No account, shelf, photo, history or internal session id. */
enum class RosterBakeState(val wire: String) { RUNNING("running"), PAUSED("paused"), SCHEDULED("scheduled"), FINALE("finale") }
data class RosterBake(val bakeKey: String, val bakeNumber: Int, val revision: Long, val recipeRevision: String,
                      val recipeName: String, val state: RosterBakeState, val stepIndex: Int, val stepCount: Int,
                      val stepTitle: String, val remainingSeconds: Long, val startAt: Long? = null,
                      val stepText: String? = null, val nextStepTitle: String? = null, val nextStepText: String? = null,
                      val quantities: String? = null, val portions: Int? = null, val controlReady: Boolean = false) {
    init {
        require(uuid(bakeKey) && bakeNumber in 1..9 && revision >= 0)
        require(recipeRevision.isNotBlank() && recipeName.isNotBlank() && stepTitle.isNotBlank())
        require(stepCount > 0 && stepIndex in 0 until stepCount && remainingSeconds >= 0)
        require((state == RosterBakeState.SCHEDULED) == (startAt != null))
        require(startAt == null || startAt > 0)
        require(portions == null || portions in 1..5)
        require(!controlReady || (portions != null && state in setOf(RosterBakeState.RUNNING, RosterBakeState.PAUSED)))
    }
}
class BakeRoster(val connectionId: String, val revision: Long, bakes: List<RosterBake>) {
    val bakes: List<RosterBake> = bakes.toList()
    init {
        require(uuid(connectionId) && revision >= 0 && this.bakes.size <= 9)
        require(this.bakes.map { it.bakeKey }.distinct().size == this.bakes.size)
        require(this.bakes.map { it.bakeNumber }.distinct().size == this.bakes.size)
    }
    internal fun payload() = json {
        addProperty("protocol_version", 2); addProperty("connection_id", connectionId); addProperty("roster_revision", revision)
        add("bakes", JsonArray().apply {
            bakes.forEach { bake -> add(json {
                addProperty("bake_key", bake.bakeKey); addProperty("bake_number", bake.bakeNumber)
                addProperty("revision", bake.revision); addProperty("recipe_revision", bake.recipeRevision)
                addProperty("recipe_name", bake.recipeName); addProperty("state", bake.state.wire)
                addProperty("step_index", bake.stepIndex); addProperty("step_count", bake.stepCount)
                addProperty("step_title", bake.stepTitle); addProperty("remaining_seconds", bake.remainingSeconds)
                if (bake.startAt == null) add("start_at", com.google.gson.JsonNull.INSTANCE) else addProperty("start_at", bake.startAt)
                fun optional(key: String, value: String?) { if (value == null) add(key, com.google.gson.JsonNull.INSTANCE) else addProperty(key, value) }
                optional("step_text", bake.stepText); optional("next_step_title", bake.nextStepTitle)
                optional("next_step_text", bake.nextStepText); optional("quantities", bake.quantities)
                if (bake.portions == null) add("portions", com.google.gson.JsonNull.INSTANCE) else addProperty("portions", bake.portions)
                addProperty("control_ready", bake.controlReady)
            }) }
        })
    }
}

enum class RosterOperation { PAUSE, RESUME, ADVANCE, BACK, CANCEL }
data class RosterCommand(val id: String, val connectionId: String, val bakeKey: String, val bakeNumber: Int,
                         val expectedRevision: Long, val recipeRevision: String, val operation: RosterOperation,
                         val issuedAt: Long, val expiresAt: Long, val leaseId: String,
                         val expectedStep: Int, val expectedPaused: Boolean) {
    fun fingerprint(): String = java.security.MessageDigest.getInstance("SHA-256")
        .digest(com.google.gson.Gson().toJson(this).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    init {
        require(uuid(id) && uuid(connectionId) && uuid(bakeKey) && bakeNumber in 1..9 && expectedRevision >= 0)
        require(uuid(leaseId) && expectedStep >= 0)
        require(recipeRevision.isNotBlank() && issuedAt > 0 && expiresAt > issuedAt && expiresAt - issuedAt <= 4000)
    }
}
data class RosterPollReply(val serverTime: Long, val commands: List<RosterCommand>)

/** Eligibility is NOT execution/APPLIED. RosterController performs the separate durable lease/receipt commit. */
object RosterCommandPolicy {
    enum class Decision { ELIGIBLE_FOR_COMMIT, REJECTED, EXPIRED, STALE, READ_ONLY }
    fun check(command: RosterCommand, roster: BakeRoster, installationConsent: Boolean,
              attached: Boolean, serverTime: Long, receivedElapsed: Long, nowElapsed: Long, nowWall: Long): Decision {
        if (!installationConsent || !attached || command.connectionId != roster.connectionId) return Decision.REJECTED
        val age = nowElapsed - receivedElapsed
        if (age !in 0..3999 || serverTime <= 0 || serverTime > Long.MAX_VALUE - age) return Decision.EXPIRED
        val projected = serverTime + age
        if (projected < command.issuedAt || projected >= command.expiresAt || nowWall < command.issuedAt ||
            nowWall >= command.expiresAt || kotlin.math.abs(projected - nowWall) >= 4000) return Decision.EXPIRED
        val bake = roster.bakes.singleOrNull { it.bakeKey == command.bakeKey } ?: return Decision.STALE
        if (bake.bakeNumber != command.bakeNumber || bake.revision != command.expectedRevision ||
            bake.recipeRevision != command.recipeRevision || bake.stepIndex != command.expectedStep ||
            (bake.state == RosterBakeState.PAUSED) != command.expectedPaused) return Decision.STALE
        if (bake.state == RosterBakeState.SCHEDULED || bake.state == RosterBakeState.FINALE) return Decision.READ_ONLY
        if (command.operation == RosterOperation.PAUSE && bake.state != RosterBakeState.RUNNING ||
            command.operation == RosterOperation.RESUME && bake.state != RosterBakeState.PAUSED) return Decision.REJECTED
        if (!bake.controlReady) return Decision.REJECTED
        // Existing commit policy must still reject advance-at-final-step/back-at-first, enforce lease and dedup.
        return Decision.ELIGIBLE_FOR_COMMIT
    }
}

interface RosterV2Api {
    suspend fun pairConsent(credential: String, request: InstallationPairConsent): PendingPairConsent
    suspend fun cancelPair(credential: String)
    suspend fun attach(credential: String): String
    suspend fun poll(installationCredential: String, roster: BakeRoster): RosterPollReply
    suspend fun disconnect(credential: String, connection: String)
    suspend fun acknowledge(credential: String, command: RosterCommand, receipt: com.polinalinen.madre.data.db.entities.VoiceCommandReceiptEntity)
}

class HttpsRosterV2Api internal constructor(private val http: ContractHttp, private val enabled: () -> Boolean) : RosterV2Api {
    constructor() : this(ContractHttp(), { FeatureFlags.FAMILY_ROSTER_V2 })
    internal constructor(client: OkHttpClient, enabled: () -> Boolean) : this(ContractHttp(client), enabled)
    private suspend fun request(path: String, credential: String, payload: com.google.gson.JsonObject, empty: Boolean = false): com.google.gson.JsonObject {
        if (!enabled()) throw ContractFailure(ContractFailure.Kind.DISABLED)
        require(credential.matches(Regex("[A-Za-z0-9_-]{43}")))
        return http.request("POST", "https://kdnfx.space/alice/madre/voice/phone/v2/$path", credential,
            payload, installation = true, noContent = empty, strict = true)
    }
    override suspend fun pairConsent(credential: String, request: InstallationPairConsent): PendingPairConsent = safe {
        val body = request("pair-consent", credential, request.payload())
        require(body.keySet() == setOf("request_id", "consent_scope", "status", "expires_at"))
        require(body.string("request_id") == request.requestId && body.string("consent_scope") == PAIR_SCOPE)
        require(body.string("status") == "pending")
        val now = System.currentTimeMillis()
        PendingPairConsent(body.string("request_id"), body.integer("expires_at")).also {
            require(it.expiresAt > now && it.expiresAt - now <= 300_000)
        }
    }
    override suspend fun cancelPair(credential: String): Unit = safe {
        if (!enabled()) throw ContractFailure(ContractFailure.Kind.DISABLED)
        require(credential.matches(Regex("[A-Za-z0-9_-]{43}")))
        val body = http.request("POST", "https://kdnfx.space/alice/madre/voice/phone/disconnect", credential,
            json {}, installation = true, strict = true)
        require(body.keySet() == setOf("revoked") && body.boolean("revoked"))
    }
    override suspend fun attach(credential: String): String = safe {
        val body = request("attach", credential, json {
            addProperty("protocol_version", 2); addProperty("consent_scope", "installation_current_bakes"); addProperty("consent", true)
        })
        require(body.keySet() == setOf("connection_id", "consent_scope", "attached"))
        require(body.boolean("attached") && body.string("consent_scope") == "installation_current_bakes")
        body.string("connection_id").also { require(uuid(it)) }
    }
    override suspend fun disconnect(credential: String, connection: String): Unit = safe {
        require(uuid(connection))
        request("disconnect", credential, json { addProperty("connection_id", connection) }, true)
        Unit
    }
    override suspend fun acknowledge(credential: String, command: RosterCommand, receipt: com.polinalinen.madre.data.db.entities.VoiceCommandReceiptEntity): Unit = safe {
        require(receipt.commandId == command.id && receipt.fingerprint == command.fingerprint())
        require(receipt.outcome in setOf("APPLIED", "STALE", "EXPIRED", "REJECTED", "PHONE_REQUIRED"))
        val body = request("ack", credential, json {
            addProperty("connection_id", command.connectionId); addProperty("command_id", command.id)
            addProperty("lease_id", command.leaseId); addProperty("outcome", receipt.outcome.lowercase(java.util.Locale.ROOT))
            if (receipt.outcome == "APPLIED") addProperty("resulting_revision", receipt.newRevision)
            else add("resulting_revision", com.google.gson.JsonNull.INSTANCE)
        })
        require(body.keySet() == setOf("acknowledged") && body.boolean("acknowledged"))
    }
    override suspend fun poll(installationCredential: String, roster: BakeRoster): RosterPollReply = safe {
        if (!enabled()) throw ContractFailure(ContractFailure.Kind.DISABLED)
        require(installationCredential.matches(Regex("[A-Za-z0-9_-]{43}")))
        val body = request("poll", installationCredential, roster.payload())
        require(body.keySet() == setOf("protocol_version", "connection_id", "roster_revision", "server_time", "commands"))
        require(body.integer("protocol_version") == 2L && body.string("connection_id") == roster.connectionId)
        require(body.integer("roster_revision") == roster.revision)
        val commands = body.getAsJsonArray("commands").also { require(it.size() <= 9) }.map { value ->
            val item = value.asJsonObject
            require(item.keySet() == setOf("id", "connection_id", "bake_key", "bake_number", "expected_revision",
                "recipe_revision", "operation", "issued_at", "expires_at", "lease_id", "expected_step", "expected_paused"))
            RosterCommand(item.string("id"), item.string("connection_id"), item.string("bake_key"),
                item.integer("bake_number").also { require(it in 1..9) }.toInt(), item.integer("expected_revision"),
                item.string("recipe_revision"), RosterOperation.valueOf(item.string("operation")),
                item.integer("issued_at"), item.integer("expires_at"), item.string("lease_id"),
                item.integer("expected_step").also { require(it <= Int.MAX_VALUE) }.toInt(), item.boolean("expected_paused")).also { require(it.connectionId == roster.connectionId) }
        }
        require(commands.map { it.id }.distinct().size == commands.size)
        require(commands.map { it.leaseId }.distinct().size == commands.size)
        RosterPollReply(body.integer("server_time").also { require(it > 0) }, commands)
    }
}
