package com.polinalinen.madre.ui.screens

import com.google.common.truth.Truth.assertThat
import java.time.LocalDate
import org.junit.Test

class BookStatsCaptionTest {
    @Test
    fun `photo caption contains real recipe and date instead of template syntax`() {
        val caption = formatStatsPhotoCaption("Хлеб на закваске", LocalDate.of(2026, 8, 3))
        assertThat(caption).isEqualTo("Хлеб на закваске, 3 августа")
        assertThat(caption).doesNotContain("${'$'}{")
    }
}
