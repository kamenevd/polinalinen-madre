package com.polinalinen.madre.integration.v2

internal const val PAIR_SCOPE = "installation_current_bakes"

/** A request identifier is technical metadata, NOT a grant or broker credential. */
data class InstallationPairConsent(val requestId: String, val consentScope: String = PAIR_SCOPE, val consent: Boolean = true) {
    init { require(uuid(requestId) && consentScope == PAIR_SCOPE && consent) }
    internal fun payload() = json {
        addProperty("protocol_version", 2); addProperty("request_id", requestId)
        addProperty("consent_scope", consentScope); addProperty("consent", consent)
    }
}
data class PendingPairConsent(val requestId: String, val expiresAt: Long) {
    init { require(uuid(requestId) && expiresAt > 0) }
}

internal enum class PairStage { IDENTITY, PROBE, CONSENT, PENDING, TERMINAL }
internal data class PairAttempt(val requestId: String, val process: String, val createdAt: Long,
    val deadline: Long, val stage: PairStage = PairStage.IDENTITY, val binding: String? = null,
    val credentialHash: String? = null, val consentExpiresAt: Long? = null) {
    fun valid(now: Long) = now >= createdAt && now < deadline
}
