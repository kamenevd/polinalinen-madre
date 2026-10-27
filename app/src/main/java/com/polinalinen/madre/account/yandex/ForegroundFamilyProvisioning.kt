package com.polinalinen.madre.account.yandex

import com.polinalinen.madre.integration.v2.ContractFailure

/** No bearer, PB credential or profile in observable provisioning state. Not voice consent. */
data class ForegroundProvisioningState(
    val phase: Phase = Phase.NOT_STARTED,
    val aliceBound: Boolean = false,
    val pocketBaseAuthenticated: Boolean = false,
    val familyError: ContractFailure.Kind? = null,
) {
    enum class Phase { NOT_STARTED, BINDING_ALICE, AUTHENTICATING_POCKETBASE, COMPLETE, RETRY_REQUIRED }
}

/** Borrows the SAME RAM owner; the controller alone closes it after both calls. */
interface ForegroundFamilyProvisioning {
    suspend fun authenticate(token: YandexLoginToken, isCurrent: () -> Boolean)
    /** Local credentials/cache only. Must never leave a server family or touch Room. */
    fun signOut()
}
