package com.polinalinen.madre.ui.screens

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class SettingsSignOutHomeUiTest {

    @Test
    fun `sign out action lives in common settings and not on shelf screen`() {
        val settingsSource = File("src/main/java/com/polinalinen/madre/ui/screens/SettingsScreen.kt")
            .readText()
        val shelfSource = File("src/main/java/com/polinalinen/madre/ui/screens/SettingsShelfScreen.kt")
            .readText()

        assertThat(settingsSource).contains("onClick = familyBookViewModel::signOut")
        assertThat(settingsSource).contains("Выйти · книга на телефоне останется")
        assertThat(shelfSource).doesNotContain("onClick = familyBookViewModel::signOut")
    }
}
