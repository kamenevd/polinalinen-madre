package com.polinalinen.madre.account.yandex

import com.polinalinen.madre.voice.VoiceApi
import com.polinalinen.madre.voice.VoiceIdentityStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Shares the encrypted installation identity, never family/email credentials or a bake grant. */
class YandexInstallationAccess(private val store: VoiceIdentityStore, private val api: VoiceApi) {
    private val enrollment = Mutex()

    /** Explicit foreground bind only; enrollment does NOT select a bake or approve a voice challenge. */
    suspend fun forBind(): String = enrollment.withLock {
        val identity = withContext(Dispatchers.IO) { store.readOrCreate() }
        api.post("enroll", identity.credential, mapOf("credential" to identity.credential))
        identity.credential
    }

    /** Never create/enroll on sign-out: a new credential cannot revoke the old binding. */
    suspend fun forUnbind(): String = withContext(Dispatchers.IO) {
        store.read()?.credential ?: throw YandexAccountFailure(YandexAccountError.SERVER)
    }
}
