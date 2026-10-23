package com.polinalinen.madre.ui.razvorot

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PaperMaterialReviewTest {
    @Test fun jarLevelUsesTheSelectedFeedingProfile() {
        val day=com.polinalinen.madre.sourdough.profileForInterval(24)
        val fridge=com.polinalinen.madre.sourdough.profileForInterval(72)
        assertThat(starterCycleLevel(12f,day.peakHours,day.cycleHours)).isNotEqualTo(starterCycleLevel(12f,fridge.peakHours,fridge.cycleHours))
        assertThat(starterCycleLevel(null,day.peakHours,day.cycleHours)).isEqualTo(0f)
    }
    @Test fun consumedClearCannotAcknowledgeSkippedPaperBuffer() {
        val frames=PaperPresentation()
        frames.queued(10L,true);frames.queued(20L,false)
        assertThat(frames.consumed(20L)).isFalse()
        assertThat(frames.consumed(10L)).isFalse()
        frames.queued(30L,true)
        assertThat(frames.consumed(30L)).isTrue()
        assertThat(frames.consumed(30L)).isFalse()
        assertThat(frames.consumed(40L)).isFalse()
    }
}
