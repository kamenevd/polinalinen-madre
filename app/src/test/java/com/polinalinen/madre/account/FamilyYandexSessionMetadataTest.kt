package com.polinalinen.madre.account

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [35], application = Application::class)
class FamilyYandexSessionMetadataTest {
    private val cipher = object : TokenCipher {
        override fun encrypt(plain: ByteArray) = byteArrayOf(7) + plain.map { (it.toInt() xor 91).toByte() }.toByteArray()
        override fun decrypt(sealed: ByteArray) = sealed.drop(1).map { (it.toInt() xor 91).toByte() }.toByteArray()
    }
    @Test fun protectedPbCredentialAndBooleanRoutingMarkerReopenTogetherAndLegacyWriteAndLogoutClearMarker() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences(SecureTokenStore.PREFS, 0).edit().clear().commit()
        val first = SecureTokenStore(context, cipher)
        first.write("SYNTHETIC_EMAIL_PB"); assertFalse(first.isYandexVerified())
        first.writeYandex("SYNTHETIC_YANDEX_PB")
        val reopened = SecureTokenStore(context, cipher)
        assertTrue(reopened.isYandexVerified()); assertEquals("SYNTHETIC_YANDEX_PB", reopened.read())
        val values = context.getSharedPreferences(SecureTokenStore.PREFS, 0).all
        assertEquals(setOf(SecureTokenStore.KEY, "family_yandex_verified"), values.keys)
        assertFalse(values.toString().contains("SYNTHETIC_YANDEX_PB")); assertFalse(values.toString().contains("OAUTH"))
        reopened.write("SYNTHETIC_OTHER_EMAIL_PB"); assertFalse(reopened.isYandexVerified())
        reopened.writeYandex("SYNTHETIC_YANDEX_PB"); reopened.clear()
        assertNull(first.read()); assertFalse(first.isYandexVerified())
    }
}
