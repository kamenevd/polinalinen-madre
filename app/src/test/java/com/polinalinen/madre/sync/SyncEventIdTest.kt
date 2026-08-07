package com.polinalinen.madre.sync

import com.google.common.truth.Truth.assertThat
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
}
