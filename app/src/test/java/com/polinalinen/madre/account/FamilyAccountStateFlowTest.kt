package com.polinalinen.madre.account

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FamilyAccountStateFlowTest {

    @Test
    fun `repository state mirrors operation results and loading keeps known account`() = runTest {
        val api = ScriptedFamilyApi()
        val tokens = InMemoryTokenStore()
        val repository = FamilyAccountRepository(api, tokens)

        val signedIn = repository.signIn("anya@example.com", "пароль")
        assertThat(repository.state.value).isEqualTo(signedIn)

        val created = repository.createFamily("Ивановы")
        assertThat(repository.state.value).isEqualTo(created)

        api.renameHandler = { _, body ->
            delay(1_000)
            com.polinalinen.madre.data.remote.FamilyResponse("f1", body.name, null)
        }
        val pendingRename = async { repository.renameFamily("Каменевы") }
        runCurrent()
        val loading = repository.state.value as FamilyBookState.Loading
        assertThat(loading.account?.familyName).isEqualTo("Ивановы")
        val renamed = pendingRename.await()
        assertThat(repository.state.value).isEqualTo(renamed)

        val left = repository.leaveFamily()
        assertThat(repository.state.value).isEqualTo(left)

        repository.signOut()
        assertThat(repository.state.value).isEqualTo(FamilyBookState.SignedOut)

        repository.signIn("anya@example.com", "пароль")
        repository.createFamily("Ивановы")
        repository.clearInviteCode()
        assertThat(repository.state.value.account?.inviteCode).isNull()

        advanceUntilIdle()
    }
}
