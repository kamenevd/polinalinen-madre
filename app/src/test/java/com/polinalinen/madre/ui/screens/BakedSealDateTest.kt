package com.polinalinen.madre.ui.screens

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class BakedSealDateTest {

    @Test
    fun `seal date is sourced from completion state and not from now`() {
        val source = File(
            "src/main/java/com/polinalinen/madre/ui/screens/BakingCompleteScreen.kt",
        ).readText()

        assertThat(source).contains("val sealMillis = completion?.completedAtMillis ?: session?.completedAt")
        assertThat(source).contains("WaxSeal(dateLabel = sealMillis?.let { romanDate(it) })")
    }
}
