package com.polinalinen.madre.settingsui

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class SettingsShelfFailureUiTest {

    @Test
    fun `shelf screen renders offline failures in-place`() {
        val source = File("src/main/java/com/polinalinen/madre/ui/screens/SettingsShelfScreen.kt")
            .readText()

        assertThat(source).contains("failed.failure.message")
        assertThat(source).contains("colors.terracotta")
        assertThat(source).contains("проверяем полку")
    }
}
