package com.polinalinen.madre.account

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FamilyAccountLockTest {

    @Test
    fun `register completes and performs register then auth without deadlock`() = runTest {
        val api = ScriptedFamilyApi()
        val repository = FamilyAccountRepository(api, InMemoryTokenStore())

        val result = withTimeout(2_000) {
            repository.register("anya@example.com", "пароль", "Аня")
        }

        assertThat(result).isInstanceOf(FamilyBookState.SignedIn::class.java)
        assertThat(api.calls).containsAtLeast("register", "auth")
    }

    @Test
    fun `all suspend operations finish under timeout and produce state transitions`() = runTest {
        val api = ScriptedFamilyApi()
        val tokens = InMemoryTokenStore("saved_token")
        api.refreshHandler = {
            com.polinalinen.madre.data.remote.AuthResponse(
                "saved_token_2",
                com.polinalinen.madre.data.remote.UserRecord("u1", "anya@example.com", "Аня", "f1"),
            )
        }
        val repository = FamilyAccountRepository(api, tokens)

        val restored = withTimeout(2_000) { repository.restore() }
        assertThat(restored).isInstanceOf(FamilyBookState.SignedIn::class.java)

        val refreshed = withTimeout(2_000) { repository.refresh() }
        assertThat(refreshed).isInstanceOf(FamilyBookState.SignedIn::class.java)

        val signedIn = withTimeout(2_000) { repository.signIn("anya@example.com", "пароль") }
        assertThat(signedIn).isInstanceOf(FamilyBookState.SignedIn::class.java)

        val created = withTimeout(2_000) { repository.createFamily("Ивановы") } as FamilyBookState.SignedIn
        assertThat(created.account.hasFamily).isTrue()

        val rotated = withTimeout(2_000) { repository.rotateInviteCode() }
        assertThat(rotated).isInstanceOf(FamilyBookState.SignedIn::class.java)

        val renamed = withTimeout(2_000) { repository.renameFamily("Каменевы") } as FamilyBookState.SignedIn
        assertThat(renamed.account.familyName).isEqualTo("Каменевы")

        val left = withTimeout(2_000) { repository.leaveFamily() } as FamilyBookState.SignedIn
        assertThat(left.account.hasFamily).isFalse()

        val joined = withTimeout(2_000) { repository.joinFamily("2W4X-6Y8Z-ABCD-EFGH") } as FamilyBookState.SignedIn
        assertThat(joined.account.hasFamily).isTrue()

        val users = withTimeout(2_000) { repository.listFamilyUsers() }
        assertThat(users).isNotEmpty()

        val reset = withTimeout(2_000) { repository.requestPasswordReset("anya@example.com") }
        assertThat(reset).isEqualTo(PasswordResetResult.Sent)
    }
}
