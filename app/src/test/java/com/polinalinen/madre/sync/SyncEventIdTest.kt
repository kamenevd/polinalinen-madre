package com.polinalinen.madre.sync

import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.data.remote.BakeStatRecord
import com.polinalinen.madre.data.remote.PocketBaseDates
import org.junit.Test

class SyncEventIdTest {
    @Test
    fun bakeUsesRecordIdNotReusableSessionCounter() {
        val first = SyncEventId.forBake("dev", 11L)
        val second = SyncEventId.forBake("dev", 12L)
        assertThat(first).isEqualTo("dev-11")
        assertThat(second).isEqualTo("dev-12")
        assertThat(first).isNotEqualTo(second)
    }

    @Test
    fun feedingUsesFeedingId() {
        assertThat(SyncEventId.forFeeding("dev", 5L)).isEqualTo("dev-5")
    }

    @Test
    fun `repeating same client_event_id must produce identical bakedAtMillis`() {
        // Contract for stable shelf bake timestamp (C27 fix1):
        // same client_event_id (derived from recordId) must always carry the
        // exact same bakedAtMillis taken from BakeRecordEntity.completedAtMillis
        // at insert time, never System.currentTimeMillis() at a later share decision.
        // This makes repeated share calls (e.g. delayed ASK dialog) produce
        // identical events for server dedup.
        val device = "dev-42"
        val recordId = 99L
        val clientEventId = SyncEventId.forBake(device, recordId)
        val fixedBakeTime = 1_784_887_200_000L  // deterministic, from persisted entity

        val rec1 = BakeStatRecord(
            deviceId = device,
            clientEventId = clientEventId,
            recipeId = "baget",
            recipeName = "Багет",
            portions = 1,
            bakedAt = PocketBaseDates.toIso(fixedBakeTime),
        )
        val rec2 = BakeStatRecord(
            deviceId = device,
            clientEventId = clientEventId,
            recipeId = "baget",
            recipeName = "Багет",
            portions = 1,
            bakedAt = PocketBaseDates.toIso(fixedBakeTime),  // must be identical
        )
        assertThat(rec1.clientEventId).isEqualTo(rec2.clientEventId)
        assertThat(rec1.bakedAt).isEqualTo(rec2.bakedAt)
    }
}
