package com.polinalinen.madre.ui.screens

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class BakedSealNotAControlTest {

    @Test
    fun `wax seal is rendered as stamp and has no click handler`() {
        val source = File(
            "src/main/java/com/polinalinen/madre/ui/screens/BakingCompleteScreen.kt",
        ).readText()
        val start = source.indexOf("private fun WaxSeal")
        require(start >= 0)
        val tail = source.substring(start, minOf(source.length, start + 260))

        assertThat(tail).contains("WaxSealStamp(")
        assertThat(tail).doesNotContain("clickable(")
        assertThat(tail).doesNotContain("bookAction(")
    }
}
