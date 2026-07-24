package com.polinalinen.madre.data.remote

import com.polinalinen.madre.BuildConfig
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Query

/**
 * PocketBase на домашнем сервере (Cycle 5). Базовый адрес — только из
 * BuildConfig.MADRE_API_URL, коллекции: bake_stats, feeding_stats,
 * margin_notes_sync. Auth нет сознательно: сервер виден только из домашней
 * сети (см. network_security_config.xml — cleartext ровно для этого хоста).
 */
interface MadreApi {

    @POST("api/collections/bake_stats/records")
    suspend fun postBakeStat(@Body record: BakeStatRecord): BakeStatRecord

    /**
     * Статистика ДРУГИХ семей: вызывающий передаёт
     * [PocketBaseFilter.excludeDevice] со своим device_id.
     */
    @GET("api/collections/bake_stats/records")
    suspend fun listBakeStats(
        @Query("filter") filter: String,
        @Query("sort") sort: String = "-baked_at",
        @Query("perPage") perPage: Int = 200,
    ): RecordsPage<BakeStatRecord>

    @POST("api/collections/feeding_stats/records")
    suspend fun postFeedingStat(@Body record: FeedingStatRecord): FeedingStatRecord

    @POST("api/collections/margin_notes_sync/records")
    suspend fun postMarginNote(@Body record: MarginNoteSyncRecord): MarginNoteSyncRecord
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
