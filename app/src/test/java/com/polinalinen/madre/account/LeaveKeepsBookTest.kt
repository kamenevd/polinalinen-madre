package com.polinalinen.madre.account

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

class LeaveKeepsBookTest {

    @Test
    fun `leave family keeps token and does not sign out`() = runTest {
        val api = ScriptedFamilyApi()
        val tokens = InMemoryTokenStore()
        val repository = FamilyAccountRepository(api, tokens)
        repository.signIn("anya@example.com", "пароль")
        repository.createFamily("Ивановы")

        val state = repository.leaveFamily() as FamilyBookState.SignedIn

        assertThat(state.account.hasFamily).isFalse()
        assertThat(tokens.token).isEqualTo("pb_token_1")
        assertThat(api.calls).contains("leave")
    }
}
