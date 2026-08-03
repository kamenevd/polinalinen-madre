package com.polinalinen.madre.account

import android.content.Context
import android.security.keystore.KeyProperties
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * DESIGN-V4.md Cycle 11: auth-токен семьи — это ключ ко всей общей книге,
 * поэтому в SharedPreferences он ложится только зашифрованным ключом из
 * Android Keystore. Сам Keystore в JVM-тестах не поднять, поэтому шифр
 * подменяется, а параметры ключа проверяются отдельно — их легко испортить
 * молча (ECB/без рандомизации), и никакой тест на round-trip этого не поймает.
 */
@RunWith(RobolectricTestRunner::class)
class SecureTokenStoreTest {

    /** Обратимая подстановка вместо AES — «шифротекст» отличим от plaintext. */
    private class ReversingCipher(var failDecrypt: Boolean = false) : TokenCipher {
        override fun encrypt(plain: ByteArray): ByteArray = plain.reversedArray()
        override fun decrypt(sealed: ByteArray): ByteArray? =
            if (failDecrypt) null else sealed.reversedArray()
    }

    private lateinit var context: Context
    private val cipher = ReversingCipher()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(SecureTokenStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun store(cipher: TokenCipher = this.cipher) = SecureTokenStore(context, cipher)

    private fun rawPreferenceValue(): String? =
        context.getSharedPreferences(SecureTokenStore.PREFS, Context.MODE_PRIVATE)
            .getString(SecureTokenStore.KEY, null)

    @Test
    fun `token round-trips through the store`() {
        val store = store()
        store.write("pb_token_abcdef")
        assertThat(store.read()).isEqualTo("pb_token_abcdef")
    }

    @Test
    fun `plaintext token never reaches shared preferences`() {
        store().write("pb_token_abcdef")
        val stored = rawPreferenceValue()
        assertThat(stored).isNotNull()
        assertThat(stored).doesNotContain("pb_token_abcdef")
    }

    @Test
    fun `missing token reads as null`() {
        assertThat(store().read()).isNull()
    }

    @Test
    fun `clear removes the stored token entirely`() {
        val store = store()
        store.write("pb_token_abcdef")
        store.clear()
        assertThat(store.read()).isNull()
        assertThat(rawPreferenceValue()).isNull()
    }

    @Test
    fun `undecryptable token reads as null instead of crashing`() {
        val failing = ReversingCipher()
        val store = store(failing)
        store.write("pb_token_abcdef")
        failing.failDecrypt = true
        assertThat(store.read()).isNull()
    }

    @Test
    fun `corrupted preference value reads as null instead of crashing`() {
        context.getSharedPreferences(SecureTokenStore.PREFS, Context.MODE_PRIVATE)
            .edit().putString(SecureTokenStore.KEY, "это не base64 !!!").commit()
        assertThat(store().read()).isNull()
    }

    @Test
    fun `only strict base64 passes the form check`() {
        assertThat(SecureTokenStore.isBase64("bWFkcmU=")).isTrue()
        // Мусор с вкраплениями букв алфавита — то, из чего Base64.decode молча
        // достаёт «полезные» байты; форму он не проходит.
        assertThat(SecureTokenStore.isBase64("это не base64 !!!")).isFalse()
        assertThat(SecureTokenStore.isBase64("bWFkcmU")).isFalse() // длина не кратна 4
        assertThat(SecureTokenStore.isBase64("bWFk cmU=")).isFalse() // пробел внутри
        assertThat(SecureTokenStore.isBase64("")).isFalse()
    }

    @Test
    fun `keystore cipher is backed by the android keystore provider`() {
        assertThat(KeystoreTokenCipher.PROVIDER).isEqualTo("AndroidKeyStore")
        assertThat(KeystoreTokenCipher.TRANSFORMATION).isEqualTo("AES/GCM/NoPadding")
    }

    @Test
    fun `keystore key spec pins AES-GCM with randomized encryption`() {
        val spec = KeystoreTokenCipher.keySpec()
        assertThat(spec.keySize).isEqualTo(256)
        assertThat(spec.blockModes.toList()).containsExactly(KeyProperties.BLOCK_MODE_GCM)
        assertThat(spec.encryptionPaddings.toList()).containsExactly(KeyProperties.ENCRYPTION_PADDING_NONE)
        assertThat(spec.isRandomizedEncryptionRequired).isTrue()
        // Биометрия/пин не требуются: книга должна открываться и без экрана блокировки.
        assertThat(spec.isUserAuthenticationRequired).isFalse()
        assertThat(spec.keystoreAlias).isEqualTo(KeystoreTokenCipher.KEY_ALIAS)
    }
}
