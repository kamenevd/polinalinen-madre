package com.polinalinen.madre.voice

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.polinalinen.madre.model.BakingSession
import java.security.MessageDigest
import java.util.UUID

/** Narrow wire command. OAuth tokens are deliberately not an authority in this model. */
data class VoiceCommand(
    val id: String,
    val operation: VoiceOperation,
    @SerializedName("bake_key") val bakeKey: String,
    @SerializedName("recipe_revision") val recipeRevision: String,
    @SerializedName("selection_generation") val selectionGeneration: String,
    @SerializedName("connection_id") val connectionId: String,
    @SerializedName("expected_revision") val expectedRevision: Long,
    @SerializedName("expected_step") val expectedStep: Int,
    @SerializedName("expected_paused") val expectedPaused: Boolean,
    @SerializedName("issued_at") val issuedAt: Long,
    @SerializedName("expires_at") val expiresAt: Long,
) {
    /** Same UUID with different bytes is rejected, not replayed as a successful receipt. */
    fun fingerprint(): String = MessageDigest.getInstance("SHA-256")
        .digest(Gson().toJson(this).toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

enum class VoiceOperation { PAUSE, RESUME, ADVANCE, BACK, CANCEL }
enum class VoiceOutcome { APPLIED, STALE, EXPIRED, REJECTED, PHONE_REQUIRED }

data class VoiceAuthority(
    val consented: Boolean,
    val attached: Boolean,
    val bakeKey: String,
    val selectionGeneration: String,
    val connectionId: String,
    val currentRecipeRevision: String,
    val serverTimeAtReceipt: Long,
)

data class VoiceDecision(val outcome: VoiceOutcome, val candidate: BakingSession? = null) {
    val removesBake: Boolean get() = outcome == VoiceOutcome.APPLIED && candidate == null
}

/** Pure proposal, NOT an acknowledgement. VM must commit state+receipt atomically before APPLIED. */
object VoiceCommandPolicy {
    fun decide(
        command: VoiceCommand,
        session: BakingSession,
        authority: VoiceAuthority,
        nowElapsed: Long,
        nowWall: Long,
        receivedElapsed: Long,
    ): VoiceDecision {
        fun deny(outcome: VoiceOutcome) = VoiceDecision(outcome)
        if (!authority.consented || !authority.attached || !uuid(command.id) ||
            !uuid(command.bakeKey) || !uuid(command.selectionGeneration) || !uuid(command.connectionId)) {
            return deny(VoiceOutcome.REJECTED)
        }
        if (command.bakeKey != session.bakeKey || command.bakeKey != authority.bakeKey ||
            command.selectionGeneration != authority.selectionGeneration ||
            command.connectionId != authority.connectionId) return deny(VoiceOutcome.REJECTED)
        // Check the server clock projected by monotonic age INSIDE the same commit fence.
        val age = nowElapsed - receivedElapsed
        val serverNow = authority.serverTimeAtReceipt + age
        if (authority.serverTimeAtReceipt <= 0 || age < 0 || age >= 4_000L ||
            serverNow < authority.serverTimeAtReceipt || kotlin.math.abs(serverNow - nowWall) >= 4_000L ||
            serverNow < command.issuedAt || serverNow >= command.expiresAt) return deny(VoiceOutcome.EXPIRED)
        // Local wall checks are an additional fail-closed fence, never an expiry extension.
        if (command.issuedAt <= 0 || command.expiresAt <= command.issuedAt ||
            command.expiresAt - command.issuedAt > 4_000L ||
            nowWall < command.issuedAt || nowWall >= command.expiresAt ||
            receivedElapsed > nowElapsed || nowElapsed - receivedElapsed >= 4_000L) {
            return deny(VoiceOutcome.EXPIRED)
        }
        if (session.recipeRevision == null || session.recipeRevision != authority.currentRecipeRevision ||
            command.recipeRevision != session.recipeRevision || !session.restoredStepValid ||
            session.trustedPortions == null || session.isCompleted) return deny(VoiceOutcome.REJECTED)
        if (command.expectedRevision < 0 || session.revision != command.expectedRevision ||
            session.currentStepIndex != command.expectedStep || session.isPaused != command.expectedPaused) {
            return deny(VoiceOutcome.STALE)
        }
        val candidate = when (command.operation) {
            VoiceOperation.PAUSE -> if (session.isPaused) return deny(VoiceOutcome.REJECTED)
                else session.togglePause(nowElapsed)
            VoiceOperation.RESUME -> if (!session.isPaused) return deny(VoiceOutcome.REJECTED)
                else session.togglePause(nowElapsed)
            VoiceOperation.ADVANCE -> if (session.isLastStep) return deny(VoiceOutcome.PHONE_REQUIRED)
                else session.advance(nowElapsed, nowWall)
            VoiceOperation.BACK -> if (session.currentStepIndex <= 0) return deny(VoiceOutcome.REJECTED)
                else session.retreat(nowElapsed, nowWall)
            VoiceOperation.CANCEL -> null
        }
        if (session.revision == Long.MAX_VALUE) return deny(VoiceOutcome.REJECTED)
        return VoiceDecision(VoiceOutcome.APPLIED, candidate?.copy(revision = session.revision + 1))
    }

    private fun uuid(value: String): Boolean = runCatching {
        UUID.fromString(value).toString() == value
    }.getOrDefault(false)
}
