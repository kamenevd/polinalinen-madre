package com.polinalinen.madre.account

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FamilyAccountStaleResponseTest {

    @Test
    fun `sign out during pending sign in keeps repository and token store signed out`() = runTest {
        val api = ScriptedFamilyApi()
        api.authHandler = {
            delay(1_000)
            com.polinalinen.madre.data.remote.AuthResponse(
                "fresh_token",
                com.polinalinen.madre.data.remote.UserRecord("u1", "anya@example.com", "Аня", ""),
            )
        }
        val tokens = InMemoryTokenStore()
        val repository = FamilyAccountRepository(api, tokens)

        val pending = async { repository.signIn("anya@example.com", "пароль") }
        runCurrent()
        repository.signOut()

        advanceUntilIdle()
        pending.await()

        assertThat(repository.state.value).isEqualTo(FamilyBookState.SignedOut)
        assertThat(tokens.token).isNull()
        assertThat(repository.currentAccount()).isNull()
    }

    @Test
    fun `clear invite code wins over stale rotate response`() = runTest {
        val api = ScriptedFamilyApi()
        val repository = FamilyAccountRepository(api, InMemoryTokenStore())
        repository.signIn("anya@example.com", "пароль")
        repository.createFamily("Ивановы")

        api.inviteHandler = { _ ->
            delay(1_000)
            com.polinalinen.madre.data.remote.FamilyResponse("f1", "Ивановы", "NEWCODE123456789")
        }

        val pending = async { repository.rotateInviteCode() }
        runCurrent()
        repository.clearInviteCode()

        advanceUntilIdle()
        pending.await()

        val account = repository.state.value.account
        assertThat(account?.inviteCode).isNull()
    }

    @Test
    fun `sign out during pending register keeps token store empty`() = runTest {
        val api = ScriptedFamilyApi()
        api.authHandler = {
            delay(1_000)
            com.polinalinen.madre.data.remote.AuthResponse(
                "registered_token",
                com.polinalinen.madre.data.remote.UserRecord("u1", "anya@example.com", "Аня", ""),
            )
        }
        val tokens = InMemoryTokenStore()
        val repository = FamilyAccountRepository(api, tokens)

        val pending = async { repository.register("anya@example.com", "пароль", "Аня") }
        runCurrent()
        repository.signOut()

        advanceUntilIdle()
        pending.await()

        assertThat(repository.state.value).isEqualTo(FamilyBookState.SignedOut)
        assertThat(tokens.token).isNull()
    }
}
