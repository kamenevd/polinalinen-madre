package com.polinalinen.madre.settingsui

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class SettingsShelfShareRemovedUiTest {

    @Test
    fun `shelf share setting row is removed from shelf settings`() {
        val source = File("src/main/java/com/polinalinen/madre/ui/screens/SettingsShelfScreen.kt")
            .readText()

        assertThat(source).doesNotContain("SettingsShelfShareRow")
        assertThat(source).doesNotContain("Ставить выпечку на полку")
    }
}
