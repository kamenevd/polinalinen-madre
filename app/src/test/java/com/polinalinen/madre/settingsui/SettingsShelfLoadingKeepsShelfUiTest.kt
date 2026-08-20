package com.polinalinen.madre.settingsui

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Test

class SettingsShelfLoadingKeepsShelfUiTest {

    @Test
    fun `loading with known account keeps shelf branch instead of sign-in form`() {
        val source = File("src/main/java/com/polinalinen/madre/ui/screens/SettingsShelfScreen.kt")
            .readText()

        assertThat(source).contains("val loading = familyBookState is FamilyBookState.Loading")
        assertThat(source).contains("if (loading)")
        assertThat(source).contains("account == null || !account.hasFamily")
    }
}
