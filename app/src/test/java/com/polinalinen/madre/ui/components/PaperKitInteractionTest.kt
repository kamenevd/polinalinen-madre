package com.polinalinen.madre.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.ui.kit.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w412dp-h915dp-mdpi")
class PaperKitInteractionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun customSwitchChangesStateAndRemainsAnAccessibleSwitch() {
        var checked = false
        rule.setContent { RazvorotTheme(force = Atmosphere.NIGHT) {
            var state by remember { mutableStateOf(false) }
            PaperSwitch(state, { state = it; checked = it })
        } }
        rule.onNode(isToggleable()).assertIsOff().performClick().assertIsOn()
        assertThat(checked).isTrue()
    }

    @Test fun disabledSwitchNeverInvokesItsCallback() {
        var calls = 0
        rule.setContent { RazvorotTheme(force = Atmosphere.DAY) {
            PaperSwitch(false, { calls++ }, enabled = false)
        } }
        rule.onNode(isToggleable()).assertIsNotEnabled()
        assertThat(calls).isEqualTo(0)
    }

    @Test fun aPaperFieldRetainsRealTextEditingAndItsError() {
        var edited = ""
        rule.setContent { RazvorotTheme(force = Atmosphere.DUSK) {
            var text by remember { mutableStateOf("") }
            PaperField(text, { text = it; edited = it }, label = "Имя", error = "Проверьте имя")
        } }
        rule.onNode(hasSetTextAction()).performTextInput("Мадре")
        rule.onNodeWithText("Мадре").assertExists()
        rule.onNodeWithText("Проверьте имя").assertExists()
        assertThat(edited).isEqualTo("Мадре")
    }
}
