package com.polinalinen.madre.data.remote

import com.polinalinen.madre.BuildConfig
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * Статистика общей книги на PocketBase `https://madre-api.kdnfx.space`:
 * коллекции bake_stats и feeding_stats.
 *
 * Cycle 17: под входом, как и всё остальное на этом сервере. До сих пор здесь
 * не было ни строки про токен — Cycle 5 писал в открытые коллекции домашнего
 * сервера, а миграция lock_legacy_collections закрыла их наглухо ещё в
 * Cycle 11. Отправка после этого не доходила ни разу, но приложение продолжало
 * показывать «отправлено»: ровно тот обман, который запрещает hard rule 8.
 * Теперь коллекции живут по семье (backend/pb_migrations/…_family_rules_for_stats),
 * и без токена сюда ходить незачем — вызывающий обязан его иметь.
 *
 * Токен едет заголовком Authorization без префикса Bearer — как его отдаёт
 * PocketBase, и как его уже передаёт [FamilyBookApi].
 *
 * Cycle 17 убрал отсюда margin_notes_sync и guest_notes — см. docs/graveyard.md
 * («Библиотечная книга» и «Гостевая страница»).
 */
interface MadreApi {

    @POST("api/collections/bake_stats/records")
    suspend fun postBakeStat(
        @Header("Authorization") token: String,
        @Body record: BakeStatRecord,
    ): BakeStatRecord

    /**
     * Выпечки СВОЕЙ семьи: сервер и так не отдаст чужих, а вызывающий сужает
     * выборку до других устройств ([PocketBaseFilter.excludeDevice]) — свои
     * выпечки книга и без сети знает лучше.
     */
    @GET("api/collections/bake_stats/records")
    suspend fun listBakeStats(
        @Header("Authorization") token: String,
        @Query("filter") filter: String,
        @Query("sort") sort: String = "-baked_at",
        @Query("perPage") perPage: Int = 200,
    ): RecordsPage<BakeStatRecord>

    @POST("api/collections/feeding_stats/records")
    suspend fun postFeedingStat(
        @Header("Authorization") token: String,
        @Body record: FeedingStatRecord,
    ): FeedingStatRecord
}

object MadreApiFactory {

    fun create(baseUrl: String = BuildConfig.MADRE_API_URL): MadreApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            // Retrofit требует baseUrl со слэшем на конце, а в BuildConfig
            // адрес хранится без него — нормализуем в одном месте.
            .baseUrl(if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(MadreApi::class.java)
    }
}
