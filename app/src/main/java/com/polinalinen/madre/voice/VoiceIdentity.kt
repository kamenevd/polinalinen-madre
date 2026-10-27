package com.polinalinen.madre.voice

import android.content.SharedPreferences
import android.util.Base64
import com.google.gson.Gson
import com.polinalinen.madre.account.TokenCipher
import java.security.SecureRandom

/** Separate capability, never the family token. Only ciphertext is persisted. */
data class VoiceIdentity(val credential: String, val bakeKey: String? = null,
    val generation: String? = null, val revokePending: Boolean = false)

class VoiceIdentityStore(private val prefs: SharedPreferences, private val cipher: TokenCipher) {
    fun read(): VoiceIdentity? {
        val value = prefs.getString("identity", null) ?: return null
        return runCatching {
            val plain = requireNotNull(cipher.decrypt(Base64.decode(value, Base64.NO_WRAP)))
            Gson().fromJson(String(plain, Charsets.UTF_8), VoiceIdentity::class.java)
                .takeIf { it.credential.matches(Regex("[A-Za-z0-9_-]{43}")) }
                ?.let { if (prefs.getBoolean("revoked", false)) it.copy(bakeKey = null, generation = null, revokePending = true) else it }
        }.getOrNull()
    }
    fun write(identity: VoiceIdentity) {
        val sealed = cipher.encrypt(Gson().toJson(identity).toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("identity", Base64.encodeToString(sealed, Base64.NO_WRAP))
            .putBoolean("revoked", identity.revokePending).commit())
    }
    fun revoke() {
        val old = read()
        if (old != null) write(old.copy(bakeKey = null, generation = null, revokePending = true))
        else check(prefs.edit().putBoolean("revoked", true).commit())
    }
    @Synchronized
    fun readOrCreate(): VoiceIdentity = read() ?: create()

    @Synchronized
    fun create(): VoiceIdentity {
        // Corruption is not permission to silently enroll another installation.
        check(!prefs.contains("identity")) { "Voice credential unavailable" }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return VoiceIdentity(Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING))
            .also(::write)
    }
}
