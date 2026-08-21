package com.polinalinen.madre.account

import com.google.common.truth.Truth.assertThat
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * DESIGN-V4.md Cycle 11: страница семейной книги обязана честно показывать,
 * что именно случилось — «не вошли», «нет сети», «идёт запрос», «ошибка», —
 * а не молча оставаться пустой. Здесь проверяется сам словарь состояний.
 */
class FamilyBookStateTest {

    private fun http(code: Int): HttpException =
        HttpException(Response.error<Any>(code, "{}".toResponseBody("application/json".toMediaType())))

    @Test
    fun `no connection is offline, not a generic error`() {
        assertThat(NetworkFailure.classify(UnknownHostException("madre-api"))).isEqualTo(NetworkFailure.OFFLINE)
        assertThat(NetworkFailure.classify(SocketTimeoutException())).isEqualTo(NetworkFailure.OFFLINE)
        assertThat(NetworkFailure.classify(IOException("порвалось"))).isEqualTo(NetworkFailure.OFFLINE)
    }

    @Test
    fun `expired or missing token asks to sign in again`() {
        assertThat(NetworkFailure.classify(http(401))).isEqualTo(NetworkFailure.SIGNED_OUT)
        assertThat(NetworkFailure.classify(http(403))).isEqualTo(NetworkFailure.SIGNED_OUT)
    }

    @Test
    fun `bad request meaning depends on the action that made it`() {
        assertThat(NetworkFailure.classify(http(400), NetworkFailure.INVALID_CREDENTIALS))
            .isEqualTo(NetworkFailure.INVALID_CREDENTIALS)
        assertThat(NetworkFailure.classify(http(400), NetworkFailure.REJECTED))
            .isEqualTo(NetworkFailure.REJECTED)
        assertThat(NetworkFailure.classify(http(404), NetworkFailure.REJECTED))
            .isEqualTo(NetworkFailure.REJECTED)
    }

    @Test
    fun `server faults and unknown throwables stay distinguishable`() {
        assertThat(NetworkFailure.classify(http(500))).isEqualTo(NetworkFailure.SERVER)
        assertThat(NetworkFailure.classify(http(503))).isEqualTo(NetworkFailure.SERVER)
        assertThat(NetworkFailure.classify(IllegalStateException("баг"))).isEqualTo(NetworkFailure.UNKNOWN)
    }

    @Test
    fun `every failure carries a readable message`() {
        NetworkFailure.entries.forEach { failure ->
            assertThat(failure.message.trim()).isNotEmpty()
        }
    }

    @Test
    fun `rejected join message never hints whether the family exists`() {
        val message = NetworkFailure.REJECTED.message.lowercase()
        listOf("не найдена", "не существует", "занят", "неверный код").forEach { leak ->
            assertThat(message).doesNotContain(leak)
        }
    }

    @Test
    fun `network actions are offered only to a signed in reader`() {
        val account = FamilyAccount(userId = "u1", email = "a@b.c", displayName = "Аня")
        assertThat(FamilyBookState.SignedIn(account).canUseNetwork).isTrue()
        assertThat(FamilyBookState.SignedOut.canUseNetwork).isFalse()
        assertThat(FamilyBookState.Loading().canUseNetwork).isFalse()
        assertThat(FamilyBookState.Failed(NetworkFailure.OFFLINE).canUseNetwork).isFalse()
    }

    @Test
    fun `a failure keeps showing the family it already knows about`() {
        val account = FamilyAccount(
            userId = "u1",
            email = "a@b.c",
            displayName = "Аня",
            familyId = "f1",
            familyName = "Ивановы",
        )
        val failed = FamilyBookState.Failed(NetworkFailure.OFFLINE, account)
        assertThat(failed.account?.familyName).isEqualTo("Ивановы")
        assertThat(account.hasFamily).isTrue()
        assertThat(account.copy(familyId = null).hasFamily).isFalse()
    }

    @Test
    fun `local book stays readable in every network state`() {
        val states = listOf(
            FamilyBookState.Loading(),
            FamilyBookState.SignedOut,
            FamilyBookState.Failed(NetworkFailure.OFFLINE),
            FamilyBookState.SignedIn(FamilyAccount("u1", "a@b.c", "Аня")),
        )
        states.forEach { assertThat(it.localBookAvailable).isTrue() }
    }
}
