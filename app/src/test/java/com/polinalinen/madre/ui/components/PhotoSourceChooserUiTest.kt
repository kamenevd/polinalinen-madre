package com.polinalinen.madre.ui.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.ui.theme.MadreTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi")
class PhotoSourceChooserUiTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `camera and gallery stay separate actions`() {
        val visible = mutableStateOf(true)
        var camera = 0
        var gallery = 0
        var dismiss = 0

        rule.setContent {
            MadreTheme {
                PhotoSourceChooser(
                    visible = visible.value,
                    onDismiss = {
                        dismiss += 1
                        visible.value = false
                    },
                    onPickGallery = { gallery += 1 },
                    onPickCamera = { camera += 1 },
                )
            }
        }

        rule.onNodeWithText("Камера").assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertThat(camera).isEqualTo(1)
        assertThat(gallery).isEqualTo(0)
        assertThat(dismiss).isEqualTo(1)

        rule.runOnIdle { visible.value = true }
        rule.waitForIdle()
        rule.onNodeWithText("Галерея").assertIsDisplayed().performClick()
        rule.waitForIdle()
        assertThat(camera).isEqualTo(1)
        assertThat(gallery).isEqualTo(1)
        assertThat(dismiss).isEqualTo(2)
    }
}
