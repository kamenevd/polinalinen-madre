package com.polinalinen.madre.ui.visual

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.ui.screens.SettingsScreen
import com.polinalinen.madre.ui.theme.MadreTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi", application = MadreApplication::class)
class SettingsStarterSectionGoldenTest {

    @get:Rule
    val rule = createComposeRule()

    @Test
    fun `starter section keeps paper layout for tap-cycle rows`() {
        rule.setContent {
            MadreTheme {
                SettingsScreen(
                    myName = "Полина",
                    onMyNameChange = {},
                    onBack = {},
                    starterName = "Соня",
                    intervalHours = 24,
                    remindersEnabled = true,
                    calmMode = true,
                )
            }
        }
        rule.onNodeWithText("ЗАКВАСКА").assertExists()
        rule.onRoot().captureRoboImage()
    }
}
