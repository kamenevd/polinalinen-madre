package com.polinalinen.madre.settingsui

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class SettingsShelfTruthUiTest {

    @Test
    fun `shelf screen keeps only leave action and uses returnable wording`() {
        val source = File("src/main/java/com/polinalinen/madre/ui/screens/SettingsShelfScreen.kt")
            .readText()

        assertThat(source).contains("Уйти с полки · можно вернуться")
        assertThat(source).doesNotContain("Выйти · книга на телефоне останется")
    }
}
