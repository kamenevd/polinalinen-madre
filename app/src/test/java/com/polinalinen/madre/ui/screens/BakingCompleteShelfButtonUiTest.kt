package com.polinalinen.madre.ui.screens

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class BakingCompleteShelfButtonUiTest {

    @Test
    fun `complete screen uses two-state shelf button and no legacy sheet`() {
        val source = File(
            "src/main/java/com/polinalinen/madre/ui/screens/BakingCompleteScreen.kt",
        ).readText()

        assertThat(source).contains("ShelfSharePolicy.DEFAULT_DECISION")
        assertThat(source).contains("ShelfSharePolicy.next(shelfDecision)")
        assertThat(source).contains("label = ShelfSharePolicy.labelOf(shelfDecision)")
        assertThat(source).contains("repeatable = true")
        assertThat(source).contains("if (sharingAvailable && session != null)")
        assertThat(source).doesNotContain("Поставить на полку?")
        assertThat(source).doesNotContain("тап — себе")
        assertThat(source).doesNotContain("AlertDialog(")
        assertThat(source).doesNotContain("ON_SHELF_STAMP")
    }
}
