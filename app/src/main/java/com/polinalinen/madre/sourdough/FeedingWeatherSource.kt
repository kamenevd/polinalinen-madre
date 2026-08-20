package com.polinalinen.madre.sourdough

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.location.Location
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.polinalinen.madre.data.remote.WeatherApi
import com.polinalinen.madre.data.remote.WeatherApiFactory
import com.polinalinen.madre.model.WeatherPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Cycle 26: погода в комментарии кормления.
 *
 * Форма кормления НИКОГДА не спрашивает разрешение: если геолокацию не давали
 * (или отозвали), книга просто молчит о погоде — комментарий остаётся
 * законным. Никакого фонового отслеживания: берётся последняя известная точка
 * грубой точности, и только если ей не больше [MAX_LOCATION_AGE_MILLIS];
 * протухшая точка — это «где телефон был вчера», и погода по ней была бы
 * выдумкой.
 *
 * Координаты никуда не сохраняются. В Room уезжает только готовая
 * человеческая строка («+18°, влажность 60%») внутри комментария — ни широты,
 * ни долготы, ни сырого ответа Open-Meteo.
 */
class FeedingWeatherSource(
    private val context: Context,
    apiProvider: () -> WeatherApi = { WeatherApiFactory.create() },
    private val hasPermission: () -> Boolean = {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED
    },
    private val nowWallClockMillis: () -> Long = { System.currentTimeMillis() },
    private val nowElapsedRealtimeNanos: () -> Long = { SystemClock.elapsedRealtimeNanos() },
    private val locationManagerProvider: () -> LocationManager? = {
        context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    },
    private val latestLocation: (Long) -> Pair<Double, Double>? = { nowMillis ->
        if (!hasPermission()) {
            null
        } else {
            val manager = locationManagerProvider()
            if (manager == null) {
                null
            } else {
                val nowNanos = nowElapsedRealtimeNanos()
                listOf(
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER,
                    LocationManager.GPS_PROVIDER,
                ).asSequence()
                    .filter { manager.allProviders.contains(it) }
                    .mapNotNull {
                        runCatching {
                            @SuppressLint("MissingPermission")
                            manager.getLastKnownLocation(it)
                        }.getOrNull()
                    }
                    .filter { isFreshLocation(nowMillis, it, nowNanos) }
                    .map { it.latitude to it.longitude }
                    .firstOrNull()
            }
        }
    },
) {

    // Retrofit собирается только тогда, когда погода действительно понадобится:
    // без разрешения на геолокацию сеть не трогается вовсе.
    private val api: WeatherApi by lazy(apiProvider)

    /** null — нет разрешения, нет свежей точки, нет сети или API не ответил. */
    suspend fun describe(nowMillis: Long = nowWallClockMillis()): String? =
        withContext(Dispatchers.IO) {
            if (!hasPermission()) return@withContext null
            runCatching {
                val point = latestLocation(nowMillis) ?: return@runCatching null
                val current = api.current(point.first, point.second).current
                WeatherPage.factualCurrent(current.temperatureC, current.humidityPercent)
            }.getOrNull()
        }

    companion object {
        /** Полчаса: погода за окном за это время не становится выдумкой. */
        const val MAX_LOCATION_AGE_MILLIS = 30 * 60 * 1000L
    }
}

private const val MAX_LOCATION_AGE_NANOS = 30 * 60 * 1_000_000_000L

internal fun isFreshLocation(
    nowWallClockMillis: Long,
    nowElapsedNanos: Long,
    locationWallClockMillis: Long,
    locationElapsedNanos: Long,
): Boolean {
    if (locationElapsedNanos > 0L) {
        if (nowElapsedNanos < locationElapsedNanos) return false
        val maxAllowedElapsed = locationElapsedNanos + MAX_LOCATION_AGE_NANOS
        if (maxAllowedElapsed < locationElapsedNanos) {
            // На краю диапазона типов эта сумма переходит за Long.MAX_VALUE.
            // В таком случае любая реально возможная разница по времени в JVM
            // считается приемлемой для практических значений системы.
            return true
        }
        return nowElapsedNanos <= maxAllowedElapsed
    }

    if (locationWallClockMillis > nowWallClockMillis) return false
    val maxAllowedWallClock = locationWallClockMillis +
        FeedingWeatherSource.MAX_LOCATION_AGE_MILLIS
    if (maxAllowedWallClock < locationWallClockMillis) {
        return true
    }
    return nowWallClockMillis <= maxAllowedWallClock
}

private fun isFreshLocation(nowWallClockMillis: Long, location: Location, nowElapsedNanos: Long): Boolean {
    return isFreshLocation(
        nowWallClockMillis = nowWallClockMillis,
        nowElapsedNanos = nowElapsedNanos,
        locationWallClockMillis = location.time,
        locationElapsedNanos = location.elapsedRealtimeNanos,
    )
}
