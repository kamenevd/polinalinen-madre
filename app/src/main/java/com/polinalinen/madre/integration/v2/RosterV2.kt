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
    init {
        require(uuid(id) && uuid(connectionId) && uuid(bakeKey) && bakeNumber in 1..9 && expectedRevision >= 0)
        require(uuid(leaseId) && expectedStep >= 0)
        require(recipeRevision.isNotBlank() && issuedAt > 0 && expiresAt > issuedAt && expiresAt - issuedAt <= 4000)
    }
}
data class RosterPollReply(val serverTime: Long, val commands: List<RosterCommand>)

/** Eligibility is NOT execution/APPLIED. Durable receipt+lease commit remains a separate next slice. */
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

class HttpsRosterV2Api internal constructor(private val http: ContractHttp, private val enabled: () -> Boolean) {
    constructor() : this(ContractHttp(), { FeatureFlags.FAMILY_ROSTER_V2 })
    internal constructor(client: OkHttpClient, enabled: () -> Boolean) : this(ContractHttp(client), enabled)
    suspend fun poll(installationCredential: String, roster: BakeRoster): RosterPollReply = safe {
        if (!enabled()) throw ContractFailure(ContractFailure.Kind.DISABLED)
        require(installationCredential.matches(Regex("[A-Za-z0-9_-]{43}")))
        val body = http.request("POST", "https://kdnfx.space/alice/madre/voice/phone/v2/poll",
            installationCredential, roster.payload(), installation = true)
        require(body.integer("protocol_version") == 2L && body.string("connection_id") == roster.connectionId)
        require(body.integer("roster_revision") == roster.revision)
        val commands = body.getAsJsonArray("commands").also { require(it.size() <= 9) }.map { value ->
            val item = value.asJsonObject
            RosterCommand(item.string("id"), item.string("connection_id"), item.string("bake_key"),
                item.integer("bake_number").also { require(it in 1..9) }.toInt(), item.integer("expected_revision"),
                item.string("recipe_revision"), RosterOperation.valueOf(item.string("operation")),
                item.integer("issued_at"), item.integer("expires_at"), item.string("lease_id"),
                item.integer("expected_step").also { require(it <= Int.MAX_VALUE) }.toInt(), item.boolean("expected_paused")).also { require(it.connectionId == roster.connectionId) }
        }
        require(commands.map { it.id }.distinct().size == commands.size)
        RosterPollReply(body.integer("server_time").also { require(it > 0) }, commands)
    }
}
