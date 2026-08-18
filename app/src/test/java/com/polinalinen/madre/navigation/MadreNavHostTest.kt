package com.polinalinen.madre.navigation

import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.data.db.entities.FeedingEntity
import com.polinalinen.madre.data.db.entities.StorageLocation
import org.junit.Test

class MadreNavHostTest {

    private fun feeding(
        id: Long,
        timestampMillis: Long,
        finalHydrationPercent: Int? = null,
        hydrationPercent: Int? = null,
    ) = FeedingEntity(
        id = id,
        sourdoughConfigId = 1,
        timestampMillis = timestampMillis,
        flourGrams = 100,
        waterGrams = 50,
        storageLocation = StorageLocation.KITCHEN,
        finalHydrationPercent = finalHydrationPercent,
        hydrationPercent = hydrationPercent,
    )

    @Test fun `latest feeding authority follows insertion order, not timestamp`() {
        val latestByInsertion = feeding(
            id = 12,
            timestampMillis = 2_000L,
            finalHydrationPercent = 70,
            hydrationPercent = 20,
        )
        val newerTimestampOlderInsertion = feeding(
            id = 10,
            timestampMillis = 5_000L,
            finalHydrationPercent = null,
            hydrationPercent = 85,
        )
        val history = listOf(latestByInsertion, newerTimestampOlderInsertion)

        assertThat(authoritativeFeedingFromHistory(history)?.id).isEqualTo(12L)
        assertThat(latestComputedHydrationFromHistory(history)).isEqualTo(70)
        assertThat(authoritativeFeedingFromHistory(history)?.timestampMillis).isEqualTo(2_000L)
    }

    @Test fun `next feeding source ignores legacy hydration in the authoritative row order`() {
        val fallbackHistory = listOf(
            feeding(id = 5, timestampMillis = 10_000L, finalHydrationPercent = null, hydrationPercent = 90),
            feeding(id = 4, timestampMillis = 9_000L, finalHydrationPercent = 72),
            feeding(id = 3, timestampMillis = 20_000L, finalHydrationPercent = 55),
        )

        assertThat(latestComputedHydrationFromHistory(fallbackHistory)).isEqualTo(72)
        assertThat(authoritativeFeedingFromHistory(fallbackHistory)?.id).isEqualTo(5L)
    }

    @Test fun `empty history has no authoritative feeding`() {
        assertThat(authoritativeFeedingFromHistory(emptyList())).isNull()
        assertThat(latestComputedHydrationFromHistory(emptyList())).isNull()
    }
}
