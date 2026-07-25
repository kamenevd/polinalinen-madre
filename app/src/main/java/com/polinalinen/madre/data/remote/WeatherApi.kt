package com.polinalinen.madre.data.remote

import com.google.gson.annotations.SerializedName
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Open-Meteo (Cycle 7, фича WeatherPage): текущая погода по координатам,
 * без ключа и регистрации — https://open-meteo.com/en/docs. HTTPS, поэтому
 * network_security_config не трогаем (cleartext разрешён только PocketBase).
 * Правила «погода → заметка Мадре» — model/WeatherPage, не здесь.
 */
interface WeatherApi {

    @GET("v1/forecast")
    suspend fun current(
        @Query("latitude") latitude: Double,
        @Query("longitude") longitude: Double,
        @Query("current") current: String = "temperature_2m,relative_humidity_2m,precipitation,weather_code",
    ): OpenMeteoResponse
}

data class OpenMeteoResponse(
    @SerializedName("current") val current: OpenMeteoCurrent,
)

data class OpenMeteoCurrent(
    @SerializedName("temperature_2m") val temperatureC: Double,
    @SerializedName("relative_humidity_2m") val humidityPercent: Int,
    @SerializedName("precipitation") val precipitationMm: Double,
    @SerializedName("weather_code") val weatherCode: Int,
)

object WeatherApiFactory {

    fun create(baseUrl: String = "https://api.open-meteo.com/"): WeatherApi {
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        return Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(WeatherApi::class.java)
    }
}
