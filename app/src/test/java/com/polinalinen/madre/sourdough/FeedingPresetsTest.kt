package com.polinalinen.madre.sourdough

import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.data.db.entities.FeedingEntity
import com.polinalinen.madre.data.db.entities.StorageLocation
import org.junit.Test

class FeedingPresetsTest {
    @Test fun `ratios preserve starter round additions and never clip at any input`() {
        for (starter in 5..250) for (multiplier in listOf(1, 2, 5)) {
            val expected = ((starter * multiplier + 2) / 5) * 5
            val result = FeedingPresets.ratio(starter, multiplier)
            if (expected in 10..300) {
                assertThat(result).isEqualTo(FeedingPresets.Masses(starter, expected, expected))
            } else assertThat(result).isNull()
        }
        assertThat(FeedingPresets.ratio(50, 2)).isEqualTo(FeedingPresets.Masses(50, 100, 100))
        assertThat(FeedingPresets.ratio(47, 2)).isEqualTo(FeedingPresets.Masses(47, 95, 95))
        assertThat(FeedingPresets.ratio(60, 5)).isEqualTo(FeedingPresets.Masses(60, 300, 300))
        assertThat(FeedingPresets.ratio(61, 5)).isNull()
    }

    @Test fun `last is exact or unavailable with no invented legacy starter or clipped mass`() {
        val feeding = FeedingEntity(sourdoughConfigId = 1, retainedStarterGrams = 47,
            flourGrams = 113, waterGrams = 59, storageLocation = StorageLocation.FRIDGE)
        assertThat(FeedingPresets.last(feeding)).isEqualTo(FeedingPresets.Masses(47, 113, 59))
        assertThat(FeedingPresets.last(null)).isNull()
        assertThat(FeedingPresets.last(feeding.copy(retainedStarterGrams = null))).isNull()
        assertThat(FeedingPresets.last(feeding.copy(retainedStarterGrams = 251))).isNull()
        assertThat(FeedingPresets.last(feeding.copy(flourGrams = 501))).isNull()
        assertThat(FeedingPresets.last(feeding.copy(waterGrams = 301))).isNull()
    }
}
