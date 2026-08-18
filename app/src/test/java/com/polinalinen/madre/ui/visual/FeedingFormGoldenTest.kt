package com.polinalinen.madre.ui.visual

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.ui.screens.FeedingFormScreen
import com.polinalinen.madre.ui.theme.MadreTheme
import com.polinalinen.madre.viewmodel.FeedingSaveState
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi", application = MadreApplication::class)
class FeedingFormGoldenTest {

    @Test
    fun `feeding form geometry in portrait`() {
        captureRoboImage {
            MadreTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FeedingFormScreen(
                        onSave = { _, _, _, _, _, _ -> },
                        onBack = {},
                        saveState = FeedingSaveState.Idle,
                        priorHydrationPercent = 72,
                    )
                }
            }
        }
    }

    @Test
    fun `feeding form saving error state`() {
        captureRoboImage {
            MadreTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    FeedingFormScreen(
                        onSave = { _, _, _, _, _, _ -> },
                        onBack = {},
                        saveState = FeedingSaveState.Error("Доступ к сети временно недоступен"),
                        priorHydrationPercent = 72,
                    )
                }
            }
        }
    }
}
