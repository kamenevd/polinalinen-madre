package com.polinalinen.madre.ui.screens

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class SettingsSignOutHomeUiTest {

    @Test
    fun `only Yandex profile owns the confirmed sign out and shelf cannot sign out`() {
        val settingsSource = File("src/main/java/com/polinalinen/madre/ui/screens/SettingsScreen.kt")
            .readText()
        val shelfSource = File("src/main/java/com/polinalinen/madre/ui/screens/SettingsShelfScreen.kt")
            .readText()

        assertThat(settingsSource).contains("familyBookViewModel::signOut")
        val profileSource = File("src/main/java/com/polinalinen/madre/ui/screens/LSettingsSurfaces.kt").readText()
        assertThat(settingsSource).doesNotContain("onClick = onSignOut")
        assertThat(settingsSource).doesNotContain("Выйти · книга на телефоне останется")
        assertThat(profileSource).contains("PaperTextAction(\"Выйти из Яндекс ID\",{confirmOut=true}")
        assertThat(profileSource).contains("if(confirmOut && owner!=null)YandexSignOutDialog")
        assertThat(profileSource).contains("owner.model.signOut()")
        assertThat(shelfSource).doesNotContain("onClick = familyBookViewModel::signOut")
    }
}
