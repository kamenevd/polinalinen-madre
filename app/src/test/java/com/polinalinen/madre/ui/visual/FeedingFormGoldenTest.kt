package com.polinalinen.madre.ui.visual

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.ui.screens.FeedingFormScreen
import com.polinalinen.madre.ui.theme.MadreTheme
import com.polinalinen.madre.viewmodel.FeedingSaveState
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi", application = MadreApplication::class)
class FeedingFormGoldenTest {

    @get:Rule
    val rule = createComposeRule()

    private fun render(state: FeedingSaveState) {
        rule.setContent {
            MadreTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FeedingFormScreen(
                        onSave = { _, _, _, _, _, _ -> },
                        onBack = {},
                        saveState = state,
                        priorHydrationPercent = 72,
                    )
                }
            }
        }
    }

    @Test
    fun `feeding form geometry in portrait`() {
        render(FeedingSaveState.Idle)
        rule.onRoot().captureRoboImage()
    }

    @Test
    fun `feeding form saving error state`() {
        render(FeedingSaveState.Error("Доступ к сети временно недоступен"))

        rule.onNodeWithText("Ошибка сохранения:", substring = true)
            .assertExists()
            .performScrollTo()
        rule.onNodeWithText("Вписать в дневник")
            .assertExists()
            .performScrollTo()

        rule.onRoot().captureRoboImage()
    }
}
