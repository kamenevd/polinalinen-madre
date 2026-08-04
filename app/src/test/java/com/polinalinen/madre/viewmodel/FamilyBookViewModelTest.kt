package com.polinalinen.madre.viewmodel

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.account.AuthTokenStore
import com.polinalinen.madre.account.FamilyAccountRepository
import com.polinalinen.madre.account.FamilyBookState
import com.polinalinen.madre.data.remote.AuthResponse
import com.polinalinen.madre.data.remote.CreateFamilyRequest
import com.polinalinen.madre.data.remote.FamilyBookApi
import com.polinalinen.madre.data.remote.FamilyRecord
import com.polinalinen.madre.data.remote.FamilyResponse
import com.polinalinen.madre.data.remote.JoinFamilyRequest
import com.polinalinen.madre.data.remote.PasswordAuthRequest
import com.polinalinen.madre.data.remote.RegisterRequest
import com.polinalinen.madre.data.remote.UserRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import retrofit2.HttpException
import retrofit2.Response

/**
 * Cycle 13, release blockers семейной книги: одноразовый код не всплывает снова
 * после того, как его показали, а двойной тап не отправляет один и тот же запрос
 * дважды. Оба свойства держатся на координаторе, поэтому проверяются здесь, а не
 * на репозитории: сеть подменена, диспетчер — ручной.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class FamilyBookViewModelTest {

    private val dispatcher = StandardTestDispatcher()

    private fun http(code: Int) =
        HttpException(Response.error<Any>(code, "{}".toResponseBody("application/json".toMediaType())))

    private class FakeTokenStore(var token: String? = null) : AuthTokenStore {
        override fun read(): String? = token
        override fun write(token: String) { this.token = token }
        override fun clear() { token = null }
    }

    private class FakeApi : FamilyBookApi {
        val calls = mutableListOf<String>()
        var createResult: () -> FamilyResponse = { FamilyResponse("f1", "Ивановы", "2W4X6Y8ZABCDEFGH") }
        var inviteResult: () -> FamilyResponse = { FamilyResponse("f1", "Ивановы", "ZYXW9876543210AB") }

        override suspend fun register(body: RegisterRequest): UserRecord =
            UserRecord("u1", "anya@example.com", "Аня", "")

        override suspend fun authWithPassword(body: PasswordAuthRequest): AuthResponse {
            calls += "auth"
            return AuthResponse("pb_token_1", UserRecord("u1", "anya@example.com", "Аня", ""))
        }

        override suspend fun authRefresh(token: String): AuthResponse =
            AuthResponse("pb_token_1", UserRecord("u1", "anya@example.com", "Аня", ""))

        override suspend fun createFamily(token: String, body: CreateFamilyRequest): FamilyResponse {
            calls += "create"
            return createResult()
        }

        override suspend fun joinFamily(token: String, body: JoinFamilyRequest): FamilyResponse {
            calls += "join"
            return FamilyResponse("f1", "Ивановы", null)
        }

        override suspend fun rotateInviteCode(token: String): FamilyResponse {
            calls += "invite"
            return inviteResult()
        }

        override suspend fun family(token: String, id: String): FamilyRecord {
            calls += "family"
            return FamilyRecord("f1", "Ивановы", "u1")
        }
    }

    private fun viewModel(api: FakeApi): FamilyBookViewModel {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val repository = FamilyAccountRepository(api, FakeTokenStore())
        return FamilyBookViewModel(app, repository)
    }

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun `a double tap on create family calls the network once`() = runTest(dispatcher) {
        val api = FakeApi()
        val vm = viewModel(api)
        vm.signIn("anya@example.com", "пароль")
        advanceUntilIdle()
        api.calls.clear()

        vm.createFamily("Ивановы")
        vm.createFamily("Ивановы")
        advanceUntilIdle()

        assertThat(api.calls.count { it == "create" }).isEqualTo(1)
    }

    @Test
    fun `a double tap on rotate calls the network once`() = runTest(dispatcher) {
        val api = FakeApi()
        val vm = viewModel(api)
        vm.signIn("anya@example.com", "пароль")
        advanceUntilIdle()
        vm.createFamily("Ивановы")
        advanceUntilIdle()
        api.calls.clear()

        vm.rotateInviteCode()
        vm.rotateInviteCode()
        advanceUntilIdle()

        assertThat(api.calls.count { it == "invite" }).isEqualTo(1)
    }

    @Test
    fun `a cleared invite code does not return when the next request fails`() = runTest(dispatcher) {
        val api = FakeApi()
        val vm = viewModel(api)
        vm.signIn("anya@example.com", "пароль")
        advanceUntilIdle()
        vm.createFamily("Ивановы")
        advanceUntilIdle()
        assertThat((vm.state.value as FamilyBookState.SignedIn).account.inviteCode).isEqualTo("2W4X6Y8ZABCDEFGH")

        vm.clearInviteCode()
        assertThat((vm.state.value as FamilyBookState.SignedIn).account.inviteCode).isNull()

        api.inviteResult = { throw http(500) }
        vm.rotateInviteCode()
        advanceUntilIdle()

        val state = vm.state.value
        assertThat(state).isInstanceOf(FamilyBookState.Failed::class.java)
        assertThat((state as FamilyBookState.Failed).account?.inviteCode).isNull()
    }
}
