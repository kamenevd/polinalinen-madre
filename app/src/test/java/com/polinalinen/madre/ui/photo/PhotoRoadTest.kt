package com.polinalinen.madre.ui.photo

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PhotoRoadTest {

    @Test
    fun `cancel is reported once per attempt and resets after attach`() {
        var road = PhotoRoad().begin()

        val first = road.cancel()
        road = first.next
        val second = road.cancel()

        assertThat(first.shouldNotify).isTrue()
        assertThat(second.shouldNotify).isFalse()

        road = road.attached().begin()
        val third = road.cancel()
        assertThat(third.shouldNotify).isTrue()
    }
}
