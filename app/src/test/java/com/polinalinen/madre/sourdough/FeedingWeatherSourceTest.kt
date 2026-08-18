package com.polinalinen.madre.sourdough

import android.content.Context
import com.google.common.truth.Truth.assertThat
import androidx.test.core.app.ApplicationProvider
import com.polinalinen.madre.data.remote.OpenMeteoCurrent
import com.polinalinen.madre.data.remote.OpenMeteoResponse
import com.polinalinen.madre.data.remote.WeatherApi
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FeedingWeatherSourceTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun apiReturning(): WeatherApi = object : WeatherApi {
        override suspend fun current(
            latitude: Double,
            longitude: Double,
            current: String,
        ) = OpenMeteoResponse(
            current = OpenMeteoCurrent(18.0, 60, 0.0, 0),
        )
    }

    private fun apiThrowing(error: Throwable): WeatherApi = object : WeatherApi {
        override suspend fun current(
            latitude: Double,
            longitude: Double,
            current: String,
        ): OpenMeteoResponse {
            throw error
        }
    }

    private fun freshByWallClock(nowWallClockMillis: Long, locationWallClockMillis: Long): Boolean {
        return isFreshLocation(
            nowWallClockMillis = nowWallClockMillis,
            nowElapsedNanos = 0L,
            locationWallClockMillis = locationWallClockMillis,
            locationElapsedNanos = 0L,
        )
    }

    @Test fun zeroToThirtyMinutesIsFreshByMonotonicClock() {
        val nowNanos = 10_000_000_000L
        val edge = FeedingWeatherSource.MAX_LOCATION_AGE_MILLIS * 1_000_000L

        assertThat(
            isFreshLocation(
                nowWallClockMillis = 1L,
                nowElapsedNanos = nowNanos,
                locationWallClockMillis = 1L,
                locationElapsedNanos = nowNanos,
            ),
        ).isTrue()
        assertThat(
            isFreshLocation(
                nowWallClockMillis = 1L,
                nowElapsedNanos = nowNanos + edge,
                locationWallClockMillis = 1L,
                locationElapsedNanos = nowNanos,
            ),
        ).isTrue()
    }

    @Test fun exactlyThirtyMinutesByWallClockIsFresh() {
        val nowWall = 5_000L
        val locationWall = nowWall - FeedingWeatherSource.MAX_LOCATION_AGE_MILLIS
        assertThat(freshByWallClock(nowWall, locationWall)).isTrue()
    }

    @Test fun staleByWallClockOrMonotonicReturnsNull() {
        assertThat(freshByWallClock(5_000L, 5_000L - FeedingWeatherSource.MAX_LOCATION_AGE_MILLIS - 1L)).isFalse()

        val nowNanos = 10_000_000_000L
        val staleNanos = FeedingWeatherSource.MAX_LOCATION_AGE_MILLIS * 1_000_000L + 1L
        assertThat(
            isFreshLocation(
                nowWallClockMillis = 1L,
                nowElapsedNanos = nowNanos + staleNanos,
                locationWallClockMillis = 1L,
                locationElapsedNanos = nowNanos,
            ),
        ).isFalse()
    }

    @Test fun futureMonotonicLocationIsNotFresh() {
        assertThat(
            isFreshLocation(
                nowWallClockMillis = 1L,
                nowElapsedNanos = 10_000L,
                locationWallClockMillis = 1L,
                locationElapsedNanos = 20_000L,
            ),
        ).isFalse()
    }

    @Test fun futureWallClockLocationFallsBackToWallAndIsRejected() {
        assertThat(freshByWallClock(1_000L, 1_001L)).isFalse()
    }

    @Test fun deniedPermissionFallsBackToNull() = runTest {
        var latestLocationCalled = false
        var apiCalled = false
        val source = FeedingWeatherSource(
            context = context,
            hasPermission = { false },
            latestLocation = {
                latestLocationCalled = true
                55.0 to 37.0
            },
            apiProvider = {
                apiCalled = true
                apiThrowing(IllegalStateException("api should not be called"))
            },
        )

        assertThat(source.describe(1_700_000_000_000L)).isNull()
        assertThat(latestLocationCalled).isFalse()
        assertThat(apiCalled).isFalse()
    }

    @Test fun locationProviderErrorFallsBackToNull() = runTest {
        val source = FeedingWeatherSource(
            context = context,
            hasPermission = { true },
            latestLocation = { throw IllegalStateException("provider error") },
            apiProvider = { apiThrowing(IllegalStateException("api should not be called")) },
        )

        assertThat(source.describe(1_700_000_000_000L)).isNull()
    }

    @Test fun apiFailureFallsBackToNull() = runTest {
        val source = FeedingWeatherSource(
            context = context,
            latestLocation = { 55.0 to 37.0 },
            apiProvider = { apiThrowing(IllegalStateException("api failed")) },
        )

        assertThat(source.describe(1_700_000_000_000L)).isNull()
    }

    @Test fun weatherApiReplyStillUsesFactAndIsReturned() = runTest {
        val source = FeedingWeatherSource(
            context = context,
            hasPermission = { true },
            latestLocation = { 55.0 to 37.0 },
            apiProvider = { apiReturning() },
        )

        assertThat(source.describe(1_700_000_000_000L)).isEqualTo("+18°, влажность 60%")
    }
}
