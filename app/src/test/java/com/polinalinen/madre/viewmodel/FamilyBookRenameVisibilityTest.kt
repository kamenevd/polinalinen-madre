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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FamilyBookRenameVisibilityTest {

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
    fun `rename in one viewmodel is immediately visible in another sharing repository`() = runTest(dispatcher) {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val api = ScriptedFamilyApi()
        val repository = FamilyAccountRepository(api, InMemoryTokenStore())
        val first = FamilyBookViewModel(app, repository)
        val second = FamilyBookViewModel(app, repository)

        first.signIn("anya@example.com", "пароль")
        advanceUntilIdle()
        first.createFamily("Ивановы")
        advanceUntilIdle()
        api.calls.clear()

        first.renameFamily("Каменевы")
        advanceUntilIdle()

        val secondState = second.state.value as FamilyBookState.SignedIn
        assertThat(secondState.account.familyName).isEqualTo("Каменевы")
        assertThat(api.calls).doesNotContain("refresh")
        assertThat(api.calls.count { it == "auth" }).isEqualTo(0)
    }
}
