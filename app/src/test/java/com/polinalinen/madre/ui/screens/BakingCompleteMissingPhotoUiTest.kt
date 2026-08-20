package com.polinalinen.madre.ui.screens

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class BakingCompleteMissingPhotoUiTest {

    @Test
    fun `main button requests photo before finish for photo decision`() {
        val source = File(
            "src/main/java/com/polinalinen/madre/ui/screens/BakingCompleteScreen.kt",
        ).readText()

        assertThat(source).contains("if (needsPhoto && photoPath == null)")
        assertThat(source).contains("pendingFinish = true")
        assertThat(source).contains("openPhotoSource()")
        assertThat(source).contains("viewModel.finish(id, shelfDecision, onHome)")
    }

    @Test
    fun `viewmodel stays fail closed when photo is required but absent`() {
        val source = File(
            "src/main/java/com/polinalinen/madre/viewmodel/BakingViewModel.kt",
        ).readText()

        assertThat(source).contains("if (ShelfSharePolicy.wantsPhoto(decision) && stagedPhoto.isNullOrBlank()) return")
    }
}
