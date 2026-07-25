package com.polinalinen.madre.ui.visual

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.polinalinen.madre.ui.components.Bookplate
import com.polinalinen.madre.ui.theme.MadreTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w360dp-h240dp-xhdpi-ru")
class BookplateGoldenTest {
    @Test
    fun familyName() {
        captureRoboImage {
            MadreTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Bookplate(
                        familyName = "Каменевы",
                        onSetName = {},
                    )
                }
            }
        }
    }
}
