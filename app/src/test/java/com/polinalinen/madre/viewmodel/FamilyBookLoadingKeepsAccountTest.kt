package com.polinalinen.madre.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.account.FamilyAccountRepository
import com.polinalinen.madre.account.FamilyBookState
import com.polinalinen.madre.account.InMemoryTokenStore
import com.polinalinen.madre.account.ScriptedFamilyApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FamilyBookLoadingKeepsAccountTest {

    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `loading during rename keeps known account`() = runTest(dispatcher) {
        val api = ScriptedFamilyApi()
        val repository = FamilyAccountRepository(api, InMemoryTokenStore())
        val app = ApplicationProvider.getApplicationContext<Application>()
        val viewModel = FamilyBookViewModel(app, repository)

        viewModel.signIn("anya@example.com", "пароль")
        advanceUntilIdle()
        viewModel.createFamily("Ивановы")
        advanceUntilIdle()

        api.renameHandler = { _, body ->
            delay(1_000)
            com.polinalinen.madre.data.remote.FamilyResponse("f1", body.name, null)
        }
        viewModel.renameFamily("Каменевы")
        runCurrent()

        val loading = viewModel.state.value as FamilyBookState.Loading
        assertThat(loading.account).isNotNull()
        assertThat(loading.account?.familyName).isEqualTo("Ивановы")

        advanceUntilIdle()
        val finished = viewModel.state.value as FamilyBookState.SignedIn
        assertThat(finished.account.familyName).isEqualTo("Каменевы")
    }
}
