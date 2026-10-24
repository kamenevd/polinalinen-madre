package com.polinalinen.madre.account.yandex

/** Existing installation + confirmed selection only. This acknowledgement is NOT a Dialogs grant. */
fun interface YandexVoiceConsentApi {
    suspend fun prepareVoiceConsent(credential: String, bakeId: String, generation: String): Long
}
