package com.polinalinen.madre.account

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Хранилище auth-токена семейной книги (DESIGN-V4.md Cycle 11, фича 28).
 *
 * Токен — ключ ко всей общей книге семьи, поэтому в SharedPreferences он
 * ложится только зашифрованным: ключ шифрования лежит в Android Keystore и
 * приложению не выдаётся даже на чтение.
 */
interface AuthTokenStore {
    fun read(): String?
    fun write(token: String)
    fun clear()
}

/** Шифр токена. Отделён от хранилища, чтобы хранилище можно было проверить. */
interface TokenCipher {
    fun encrypt(plain: ByteArray): ByteArray
    /** null — расшифровать нечем или нечего: ключ сменился, данные испорчены. */
    fun decrypt(sealed: ByteArray): ByteArray?
}

/**
 * SharedPreferences + [TokenCipher]. Любая порча значения читается как
 * «токена нет»: читателя попросят войти заново — это неприятно, но честно,
 * а падать при открытии настроек нельзя.
 */
class SecureTokenStore(
    context: Context,
    private val cipher: TokenCipher,
) : AuthTokenStore {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    override fun read(): String? {
        val stored = prefs.getString(KEY, null) ?: return null
        // Проверяем форму строки САМИ, до декодера. android.util.Base64 не
        // ругается на мусор — он молча пропускает всё, чего нет в алфавите, и
        // из «это не base64 !!!» достаёт четыре байта из букв b-a-s-e-6-4.
        // Такой «токен» дошёл бы до шифра и с невезучим шифром расшифровался
        // бы в мусор, а не в честное «токена нет».
        if (!isBase64(stored)) return null
        val sealed = runCatching { Base64.decode(stored, Base64.NO_WRAP) }.getOrNull() ?: return null
        val plain = runCatching { cipher.decrypt(sealed) }.getOrNull() ?: return null
        return String(plain, Charsets.UTF_8).takeIf { it.isNotEmpty() }
    }

    override fun write(token: String) {
        val sealed = cipher.encrypt(token.toByteArray(Charsets.UTF_8))
        prefs.edit().putString(KEY, Base64.encodeToString(sealed, Base64.NO_WRAP)).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    companion object {
        const val PREFS = "madre_account"
        const val KEY = "family_auth_token"

        private val BASE64 = Regex("""^[A-Za-z0-9+/]+={0,2}$""")

        /** Строгая форма Base64 без переносов: то, что кладёт [write], и ничего кроме. */
        fun isBase64(value: String): Boolean =
            value.length % 4 == 0 && BASE64.matches(value)
    }
}

/**
 * AES-GCM на ключе из Android Keystore.
 *
 * Параметры ключа вынесены в [keySpec] отдельно: испортить их можно молча
 * (ECB вместо GCM, детерминированное шифрование), и никакой round-trip этого
 * не заметит. Вектор инициализации кладётся перед шифротекстом — GCM требует,
 * чтобы он был новым на каждую запись, и Keystore за этим следит сам.
 */
class KeystoreTokenCipher : TokenCipher {

    override fun encrypt(plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        return cipher.iv + cipher.doFinal(plain)
    }

    override fun decrypt(sealed: ByteArray): ByteArray? {
        if (sealed.size <= IV_LENGTH) return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(TAG_BITS, sealed, 0, IV_LENGTH),
            )
            cipher.doFinal(sealed, IV_LENGTH, sealed.size - IV_LENGTH)
        }.getOrNull()
    }

    private fun key(): SecretKey {
        val keystore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keystore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(keySpec())
        return generator.generateKey()
    }

    companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_ALIAS = "madre_family_auth_token"

        private const val IV_LENGTH = 12
        private const val TAG_BITS = 128

        /**
         * Биометрия и пин не требуются сознательно: книга должна открываться и
         * на телефоне без экрана блокировки.
         */
        fun keySpec(): KeyGenParameterSpec =
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build()
    }
}
