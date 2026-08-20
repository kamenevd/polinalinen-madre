package com.polinalinen.madre.account

import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.data.remote.FamilyBookApi
import com.polinalinen.madre.data.remote.PasswordResetRequest
import org.junit.Test

/**
 * Cycle 27: сброс пароля — форма входа, не отдельная глава. Проверяется
 * форма вызова и то, что книга на телефоне в текстах отказа остаётся.
 */
class PasswordResetTest {

    @Test
    fun `the reset call is pocketbase request-password-reset with email only`() {
        assertThat(PasswordReset.PATH).isEqualTo("api/collections/users/request-password-reset")
        val json = com.google.gson.Gson().toJson(
            PasswordResetRequest("anya@example.com"),
        )
        assertThat(json).isEqualTo("{\"email\":\"anya@example.com\"}")
        assertThat(json).doesNotContain("identity")
        assertThat(json).doesNotContain("password")
        val post = FamilyBookApi::class.java.methods
            .single { it.name == "requestPasswordReset" }
            .getAnnotation(retrofit2.http.POST::class.java)
        assertThat(post).isNotNull()
        assertThat(post!!.value).isEqualTo(PasswordReset.PATH)
    }

    @Test
    fun `copy lives under the password and names the mailbox`() {
        assertThat(PasswordReset.ACTION_LABEL)
            .isEqualTo("Забыли пароль? Пришлём письмо с kdnfx@kdnfx.ru")
        assertThat(PasswordReset.SENT_LINE).isEqualTo("письмо ушло, загляните в спам")
        assertThat(PasswordReset.ACTION_LABEL).contains("kdnfx@kdnfx.ru")
    }

    @Test
    fun `every failure says the book on the phone stays`() {
        val offline = PasswordReset.messageFor(NetworkFailure.OFFLINE)
        assertThat(offline).contains("Книга на телефоне")
        assertThat(PasswordReset.messageFor(NetworkFailure.UNKNOWN)).contains("Книга на телефоне")
        assertThat(offline.lowercase()).doesNotContain("общая книга")
    }
}
