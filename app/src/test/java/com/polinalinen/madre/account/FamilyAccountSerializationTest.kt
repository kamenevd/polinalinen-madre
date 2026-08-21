package com.polinalinen.madre.account

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test

class FamilyAccountSerializationTest {

    @Test
    fun `refresh cannot overwrite a later rename with stale family name`() = runTest {
        val api = ScriptedFamilyApi()
        val repository = FamilyAccountRepository(api, InMemoryTokenStore())
        repository.signIn("anya@example.com", "пароль")
        repository.createFamily("Ивановы")

        api.familyHandler = { _, _ ->
            delay(1_000)
            com.polinalinen.madre.data.remote.FamilyRecord("f1", "Ивановы", "u1")
        }
        api.renameHandler = { _, body ->
            com.polinalinen.madre.data.remote.FamilyResponse("f1", body.name, null)
        }

        val refresh = async { repository.refresh() }
        runCurrent()
        val rename = async { repository.renameFamily("Каменевы") }

        advanceUntilIdle()
        refresh.await()
        rename.await()

        val final = repository.state.value as FamilyBookState.SignedIn
        assertThat(final.account.familyName).isEqualTo("Каменевы")
    }
}
