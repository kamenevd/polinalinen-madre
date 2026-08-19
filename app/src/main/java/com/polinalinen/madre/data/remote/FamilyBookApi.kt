package com.polinalinen.madre.data.remote

import com.polinalinen.madre.BuildConfig
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.PATCH
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * «Семейная книга» (Cycle 11): вход и общие книги на PocketBase
 * `https://madre-api.kdnfx.space`. В отличие от [MadreApi] здесь всё под
 * входом — токен едет заголовком Authorization, как его отдаёт PocketBase
 * (без префикса Bearer).
 *
 * Токен передаётся аргументом, а не перехватчиком: у приложения нет
 * «глобально вошедшего» состояния — локальная книга работает и без аккаунта,
 * и вызывающий обязан явно показать, что токен у него есть.
 */
interface FamilyBookApi {

    @POST("api/collections/users/records")
    suspend fun register(@Body body: RegisterRequest): UserRecord

    @POST("api/collections/users/auth-with-password")
    suspend fun authWithPassword(@Body body: PasswordAuthRequest): AuthResponse

    /**
     * Письмо со сбросом. PocketBase отвечает 204 без тела — Gson такое не
     * читает, поэтому [Response] с [ResponseBody], а не data-класс.
     */
    @POST("api/collections/users/request-password-reset")
    suspend fun requestPasswordReset(@Body body: PasswordResetRequest): Response<ResponseBody>

    @POST("api/collections/users/auth-refresh")
    suspend fun authRefresh(@Header("Authorization") token: String): AuthResponse

    @POST("api/madre/family/create")
    suspend fun createFamily(
        @Header("Authorization") token: String,
        @Body body: CreateFamilyRequest,
    ): FamilyResponse

    @POST("api/madre/family/join")
    suspend fun joinFamily(
        @Header("Authorization") token: String,
        @Body body: JoinFamilyRequest,
    ): FamilyResponse

    @POST("api/madre/family/invite")
    suspend fun rotateInviteCode(@Header("Authorization") token: String): FamilyResponse

    @GET("api/collections/families/records/{id}")
    suspend fun family(
        @Header("Authorization") token: String,
        @Path("id") id: String,
    ): FamilyRecord

    @PATCH("api/madre/family/rename")
    suspend fun renameFamily(
        @Header("Authorization") token: String,
        @Body body: RenameFamilyRequest,
    ): FamilyResponse

    @POST("api/madre/family/leave")
    suspend fun leaveFamily(@Header("Authorization") token: String): LeaveFamilyResponse

    /**
     * Участники своей полки. Правило коллекции users и так не отдаст чужих;
     * фильтр здесь не нужен — один аккаунт остаётся одним корешком на клиенте.
     */
    @GET("api/collections/users/records")
    suspend fun listFamilyUsers(
        @Header("Authorization") token: String,
        @Query("perPage") perPage: Int = 50,
        @Query("fields") fields: String = "id,email,name,family",
    ): RecordsPage<UserRecord>
}

object FamilyBookApiFactory {

    fun create(baseUrl: String = BuildConfig.MADRE_API_URL): FamilyBookApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(FamilyBookApi::class.java)
    }
}
