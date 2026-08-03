package com.polinalinen.madre.account

import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.data.remote.AuthResponse
import com.polinalinen.madre.data.remote.CreateFamilyRequest
import com.polinalinen.madre.data.remote.FamilyBookApi
import com.polinalinen.madre.data.remote.FamilyRecord
import com.polinalinen.madre.data.remote.FamilyResponse
import com.polinalinen.madre.data.remote.JoinFamilyRequest
import com.polinalinen.madre.data.remote.PasswordAuthRequest
import com.polinalinen.madre.data.remote.RegisterRequest
import com.polinalinen.madre.data.remote.UserRecord
import java.net.UnknownHostException
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response

/**
 * DESIGN-V4.md Cycle 11, «Семейная книга»: вход, создание книги и вступление
 * по коду. Проверяется ровно то, что видно снаружи — какое состояние получает
 * страница и что происходит с токеном; сети здесь нет, api подменён.
 */
class FamilyAccountRepositoryTest {

    private fun http(code: Int) =
        HttpException(Response.error<Any>(code, "{}".toResponseBody("application/json".toMediaType())))

    private class FakeTokenStore(var token: String? = null) : AuthTokenStore {
        override fun read(): String? = token
        override fun write(token: String) { this.token = token }
        override fun clear() { token = null }
    }

    private class FakeApi : FamilyBookApi {
        val calls = mutableListOf<String>()
        var seenAuthorization: String? = null

        var registerResult: () -> UserRecord = { UserRecord("u1", "anya@example.com", "Аня", "") }
        var authResult: () -> AuthResponse = { AuthResponse("pb_token_1", UserRecord("u1", "anya@example.com", "Аня", "")) }
        var refreshResult: () -> AuthResponse = authResult
        var createResult: () -> FamilyResponse = { FamilyResponse("f1", "Ивановы", "2W4X6Y8ZABCDEFGH") }
        var joinResult: () -> FamilyResponse = { FamilyResponse("f1", "Ивановы", null) }
        var inviteResult: () -> FamilyResponse = { FamilyResponse("f1", "Ивановы", "ZYXW9876543210AB") }
        var familyResult: () -> FamilyRecord = { FamilyRecord("f1", "Ивановы", "u1") }

        override suspend fun register(body: RegisterRequest): UserRecord {
            calls += "register"
            return registerResult()
        }

        override suspend fun authWithPassword(body: PasswordAuthRequest): AuthResponse {
            calls += "auth"
            return authResult()
        }

        override suspend fun authRefresh(token: String): AuthResponse {
            calls += "refresh"
            seenAuthorization = token
            return refreshResult()
        }

        override suspend fun createFamily(token: String, body: CreateFamilyRequest): FamilyResponse {
            calls += "create"
            seenAuthorization = token
            return createResult()
        }

        override suspend fun joinFamily(token: String, body: JoinFamilyRequest): FamilyResponse {
            calls += "join"
            seenAuthorization = token
            return joinResult()
        }

        override suspend fun rotateInviteCode(token: String): FamilyResponse {
            calls += "invite"
            seenAuthorization = token
            return inviteResult()
        }

        override suspend fun family(token: String, id: String): FamilyRecord {
            calls += "family"
            seenAuthorization = token
            return familyResult()
        }
    }

    private fun repository(api: FakeApi = FakeApi(), tokens: FakeTokenStore = FakeTokenStore()) =
        FamilyAccountRepository(api, tokens)

    // ── Без входа книга просто работает ──────────────────────────────────

    @Test
    fun `without a stored token the book is signed out and no request is made`() = runTest {
        val api = FakeApi()
        val state = repository(api).restore()
        assertThat(state).isEqualTo(FamilyBookState.SignedOut)
        assertThat(api.calls).isEmpty()
    }

    @Test
    fun `a stored token restores the signed in reader`() = runTest {
        val api = FakeApi()
        api.refreshResult = { AuthResponse("pb_token_2", UserRecord("u1", "anya@example.com", "Аня", "f1")) }
        val tokens = FakeTokenStore("pb_token_1")

        val state = repository(api, tokens).restore()

        assertThat(state).isInstanceOf(FamilyBookState.SignedIn::class.java)
        val account = (state as FamilyBookState.SignedIn).account
        assertThat(account.displayName).isEqualTo("Аня")
        assertThat(account.familyId).isEqualTo("f1")
        assertThat(account.familyName).isEqualTo("Ивановы")
        assertThat(tokens.token).isEqualTo("pb_token_2")
    }

    @Test
    fun `restore sends the stored token as the authorization header`() = runTest {
        val api = FakeApi()
        repository(api, FakeTokenStore("pb_token_1")).restore()
        assertThat(api.seenAuthorization).isEqualTo("pb_token_1")
    }

    @Test
    fun `a rejected token is dropped so the reader is asked to sign in again`() = runTest {
        val api = FakeApi()
        api.refreshResult = { throw http(401) }
        val tokens = FakeTokenStore("протухший")

        val state = repository(api, tokens).restore()

        assertThat(state).isEqualTo(FamilyBookState.Failed(NetworkFailure.SIGNED_OUT))
        assertThat(tokens.token).isNull()
    }

    @Test
    fun `being offline never throws away a good token`() = runTest {
        val api = FakeApi()
        api.refreshResult = { throw UnknownHostException("madre-api.kdnfx.space") }
        val tokens = FakeTokenStore("pb_token_1")

        val state = repository(api, tokens).restore()

        assertThat(state).isEqualTo(FamilyBookState.Failed(NetworkFailure.OFFLINE))
        assertThat(tokens.token).isEqualTo("pb_token_1")
    }

    // ── Регистрация и вход ───────────────────────────────────────────────

    @Test
    fun `signing in stores the token and reports the account`() = runTest {
        val api = FakeApi()
        val tokens = FakeTokenStore()

        val state = repository(api, tokens).signIn("anya@example.com", "пароль")

        assertThat(state).isInstanceOf(FamilyBookState.SignedIn::class.java)
        assertThat(tokens.token).isEqualTo("pb_token_1")
        assertThat(api.calls).containsExactly("auth")
    }

    @Test
    fun `wrong credentials do not leave a token behind`() = runTest {
        val api = FakeApi()
        api.authResult = { throw http(400) }
        val tokens = FakeTokenStore()

        val state = repository(api, tokens).signIn("anya@example.com", "не тот")

        assertThat(state).isEqualTo(FamilyBookState.Failed(NetworkFailure.INVALID_CREDENTIALS))
        assertThat(tokens.token).isNull()
    }

    @Test
    fun `registration signs the reader in right away`() = runTest {
        val api = FakeApi()
        val tokens = FakeTokenStore()

        val state = repository(api, tokens).register("anya@example.com", "длинный-пароль", "Аня")

        assertThat(state).isInstanceOf(FamilyBookState.SignedIn::class.java)
        assertThat(api.calls).containsExactly("register", "auth").inOrder()
        assertThat(tokens.token).isEqualTo("pb_token_1")
    }

    @Test
    fun `a taken email fails without signing anybody in`() = runTest {
        val api = FakeApi()
        api.registerResult = { throw http(400) }
        val tokens = FakeTokenStore()

        val state = repository(api, tokens).register("anya@example.com", "пароль", "Аня")

        assertThat(state).isEqualTo(FamilyBookState.Failed(NetworkFailure.INVALID_CREDENTIALS))
        assertThat(api.calls).containsExactly("register")
        assertThat(tokens.token).isNull()
    }

    // ── Создание книги и вступление ──────────────────────────────────────

    @Test
    fun `creating a family without signing in never touches the network`() = runTest {
        val api = FakeApi()
        val state = repository(api).createFamily("Ивановы")
        assertThat(state).isEqualTo(FamilyBookState.Failed(NetworkFailure.SIGNED_OUT))
        assertThat(api.calls).isEmpty()
    }

    @Test
    fun `creating a family shows the invite code once`() = runTest {
        val api = FakeApi()
        val repository = repository(api, FakeTokenStore())
        repository.signIn("anya@example.com", "пароль")

        val state = repository.createFamily("Ивановы")

        val account = (state as FamilyBookState.SignedIn).account
        assertThat(account.familyId).isEqualTo("f1")
        assertThat(account.familyName).isEqualTo("Ивановы")
        assertThat(account.inviteCode).isEqualTo("2W4X6Y8ZABCDEFGH")
    }

    @Test
    fun `a malformed invite code is refused before any request goes out`() = runTest {
        val api = FakeApi()
        val repository = repository(api, FakeTokenStore())
        repository.signIn("anya@example.com", "пароль")
        api.calls.clear()

        val state = repository.joinFamily("не код")

        assertThat(state).isEqualTo(FamilyBookState.Failed(NetworkFailure.REJECTED))
        assertThat(api.calls).isEmpty()
    }

    @Test
    fun `a wrong invite code fails exactly like a malformed one`() = runTest {
        val api = FakeApi()
        api.joinResult = { throw http(400) }
        val repository = repository(api, FakeTokenStore())
        repository.signIn("anya@example.com", "пароль")

        val wrong = repository.joinFamily("2W4X-6Y8Z-ABCD-EFGH")
        val malformed = repository.joinFamily("не код")

        assertThat(wrong).isEqualTo(malformed)
        assertThat(wrong).isEqualTo(FamilyBookState.Failed(NetworkFailure.REJECTED))
    }

    @Test
    fun `joining normalizes the code the reader typed by hand`() = runTest {
        val api = FakeApi()
        val repository = repository(api, FakeTokenStore())
        repository.signIn("anya@example.com", "пароль")

        val state = repository.joinFamily(" 2w4x-6y8z abcd efgh ")

        val account = (state as FamilyBookState.SignedIn).account
        assertThat(account.familyName).isEqualTo("Ивановы")
        assertThat(account.inviteCode).isNull()
        assertThat(api.calls).contains("join")
    }

    @Test
    fun `rotating the invite code hands back a fresh one`() = runTest {
        val api = FakeApi()
        val repository = repository(api, FakeTokenStore())
        repository.signIn("anya@example.com", "пароль")
        repository.createFamily("Ивановы")

        val state = repository.rotateInviteCode()

        assertThat((state as FamilyBookState.SignedIn).account.inviteCode).isEqualTo("ZYXW9876543210AB")
    }

    @Test
    fun `losing the connection while joining reports offline, not rejection`() = runTest {
        val api = FakeApi()
        api.joinResult = { throw UnknownHostException("madre-api.kdnfx.space") }
        val repository = repository(api, FakeTokenStore())
        repository.signIn("anya@example.com", "пароль")

        val state = repository.joinFamily("2W4X-6Y8Z-ABCD-EFGH")

        assertThat(state).isInstanceOf(FamilyBookState.Failed::class.java)
        assertThat((state as FamilyBookState.Failed).failure).isEqualTo(NetworkFailure.OFFLINE)
        assertThat(state.account?.email).isEqualTo("anya@example.com")
    }

    // ── Выход ────────────────────────────────────────────────────────────

    @Test
    fun `signing out forgets the token and the account`() = runTest {
        val api = FakeApi()
        val tokens = FakeTokenStore()
        val repository = repository(api, tokens)
        repository.signIn("anya@example.com", "пароль")

        assertThat(repository.signOut()).isEqualTo(FamilyBookState.SignedOut)
        assertThat(tokens.token).isNull()
        assertThat(repository.createFamily("Ивановы")).isEqualTo(FamilyBookState.Failed(NetworkFailure.SIGNED_OUT))
    }
}
