package com.polinalinen.madre.account

import com.polinalinen.madre.data.remote.AuthResponse
import com.polinalinen.madre.data.remote.CreateFamilyRequest
import com.polinalinen.madre.data.remote.FamilyBookApi
import com.polinalinen.madre.data.remote.FamilyRecord
import com.polinalinen.madre.data.remote.FamilyResponse
import com.polinalinen.madre.data.remote.JoinFamilyRequest
import com.polinalinen.madre.data.remote.LeaveFamilyResponse
import com.polinalinen.madre.data.remote.PasswordAuthRequest
import com.polinalinen.madre.data.remote.PasswordResetRequest
import com.polinalinen.madre.data.remote.RecordsPage
import com.polinalinen.madre.data.remote.RegisterRequest
import com.polinalinen.madre.data.remote.RenameFamilyRequest
import com.polinalinen.madre.data.remote.UserRecord
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.HttpException
import retrofit2.Response

internal fun http(code: Int): HttpException =
    HttpException(Response.error<Any>(code, "{}".toResponseBody("application/json".toMediaType())))

internal fun noContent(): Response<ResponseBody> {
    val raw = okhttp3.Response.Builder()
        .request(Request.Builder().url("https://madre-api.kdnfx.space/").build())
        .protocol(Protocol.HTTP_1_1)
        .code(204)
        .message("No Content")
        .build()
    return Response.success(null, raw)
}

internal class InMemoryTokenStore(var token: String? = null) : AuthTokenStore {
    override fun read(): String? = token
    override fun write(token: String) {
        this.token = token
    }
    override fun clear() {
        token = null
    }
}

internal class ScriptedFamilyApi : FamilyBookApi {
    val calls = mutableListOf<String>()
    var seenAuthorization: String? = null

    var registerHandler: suspend (RegisterRequest) -> UserRecord =
        { UserRecord("u1", "anya@example.com", "Аня", "") }
    var authHandler: suspend (PasswordAuthRequest) -> AuthResponse =
        { AuthResponse("pb_token_1", UserRecord("u1", "anya@example.com", "Аня", "")) }
    var refreshHandler: suspend (String) -> AuthResponse = { token ->
        AuthResponse("pb_token_1", UserRecord("u1", "anya@example.com", "Аня", "f1"))
    }
    var createHandler: suspend (String, CreateFamilyRequest) -> FamilyResponse =
        { _, _ -> FamilyResponse("f1", "Ивановы", "2W4X6Y8ZABCDEFGH") }
    var joinHandler: suspend (String, JoinFamilyRequest) -> FamilyResponse =
        { _, _ -> FamilyResponse("f1", "Ивановы", null) }
    var inviteHandler: suspend (String) -> FamilyResponse =
        { _ -> FamilyResponse("f1", "Ивановы", "ZYXW9876543210AB") }
    var familyHandler: suspend (String, String) -> FamilyRecord =
        { _, _ -> FamilyRecord("f1", "Ивановы", "u1") }
    var renameHandler: suspend (String, RenameFamilyRequest) -> FamilyResponse =
        { _, body -> FamilyResponse("f1", body.name, null) }
    var leaveHandler: suspend (String) -> LeaveFamilyResponse =
        { _ -> LeaveFamilyResponse(ok = true) }
    var usersHandler: suspend (String, Int, String) -> RecordsPage<UserRecord> =
        { _, perPage, _ -> RecordsPage(1, perPage, 1, listOf(UserRecord("u1", "anya@example.com", "Аня", "f1"))) }
    var resetHandler: suspend (PasswordResetRequest) -> Response<ResponseBody> = { noContent() }

    override suspend fun register(body: RegisterRequest): UserRecord {
        calls += "register"
        return registerHandler(body)
    }

    override suspend fun authWithPassword(body: PasswordAuthRequest): AuthResponse {
        calls += "auth"
        return authHandler(body)
    }

    override suspend fun requestPasswordReset(body: PasswordResetRequest): Response<ResponseBody> {
        calls += "reset"
        return resetHandler(body)
    }

    override suspend fun authRefresh(token: String): AuthResponse {
        calls += "refresh"
        seenAuthorization = token
        return refreshHandler(token)
    }

    override suspend fun createFamily(token: String, body: CreateFamilyRequest): FamilyResponse {
        calls += "create"
        seenAuthorization = token
        return createHandler(token, body)
    }

    override suspend fun joinFamily(token: String, body: JoinFamilyRequest): FamilyResponse {
        calls += "join"
        seenAuthorization = token
        return joinHandler(token, body)
    }

    override suspend fun rotateInviteCode(token: String): FamilyResponse {
        calls += "invite"
        seenAuthorization = token
        return inviteHandler(token)
    }

    override suspend fun family(token: String, id: String): FamilyRecord {
        calls += "family"
        seenAuthorization = token
        return familyHandler(token, id)
    }

    override suspend fun renameFamily(token: String, body: RenameFamilyRequest): FamilyResponse {
        calls += "rename"
        seenAuthorization = token
        return renameHandler(token, body)
    }

    override suspend fun leaveFamily(token: String): LeaveFamilyResponse {
        calls += "leave"
        seenAuthorization = token
        return leaveHandler(token)
    }

    override suspend fun listFamilyUsers(token: String, perPage: Int, fields: String): RecordsPage<UserRecord> {
        calls += "users"
        seenAuthorization = token
        return usersHandler(token, perPage, fields)
    }
}
