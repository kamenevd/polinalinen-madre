package com.polinalinen.madre.ui.screens

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class BakingCompleteBusyWhileFinishingUiTest {

    @Test
    fun `busy state disables home and photo controls`() {
        val source = File(
            "src/main/java/com/polinalinen/madre/ui/screens/BakingCompleteScreen.kt",
        ).readText()

        assertThat(source).contains("val busy = sessionId != null && finishing.contains(sessionId)")
        assertThat(source).contains("BackHandler(enabled = !busy)")
        assertThat(source).contains("label = \"На главную\"")
        assertThat(source).contains("enabled = !busy")
        assertThat(source).contains("viewModel.unstageBakePhoto(sessionId)")
        assertThat(source).contains("enabled = !busy,")
    }
}
