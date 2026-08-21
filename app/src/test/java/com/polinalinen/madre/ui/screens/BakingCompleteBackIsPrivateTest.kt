package com.polinalinen.madre.ui.screens

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class BakingCompleteBackIsPrivateTest {

    @Test
    fun `system back routes to keep decision`() {
        val source = File(
            "src/main/java/com/polinalinen/madre/ui/screens/BakingCompleteScreen.kt",
        ).readText()

        assertThat(source).contains("BackHandler {")
        assertThat(source).contains("if (busy) return@BackHandler")
        assertThat(source).contains("viewModel.finish(id, ShelfShareDecision.KEEP, onHome)")
        assertThat(source).doesNotContain("if (session == null) return@BackHandler onHome()")
        assertThat(source).contains("viewModel.finish(id, shelfDecision, onHome)")
    }
}
