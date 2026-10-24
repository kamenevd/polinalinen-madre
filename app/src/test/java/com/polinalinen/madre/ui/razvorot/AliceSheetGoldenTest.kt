package com.polinalinen.madre.ui.razvorot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.*
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.repository.RecipeRepository
import com.polinalinen.madre.ui.kit.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],qualifiers="ru-w412dp-h915dp-mdpi",application=MadreApplication::class)
class AliceSheetGoldenTest {
    @Test fun sixFStatesInFourAtmospheresManualOnly() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val recipes = runBlocking { RecipeRepository(context).getRecipes() }
        val module = if (File("app/src/main/AndroidManifest.xml").isFile) File("app") else File(".")
        for (mode in listOf("unlinked", "listening", "reading", "confirm", "offline", "phone_required")) {
            for (theme in listOf(Atmosphere.DAWN, Atmosphere.DAY, Atmosphere.DUSK, Atmosphere.NIGHT)) {
                val file = File(module, "build/outputs/roborazzi/alice/alice-$mode-${theme.captureName()}.png")
                file.parentFile.mkdirs()
                captureRoboImage(filePath=file.absolutePath, roborazziOptions=RoborazziOptions(taskType=RoborazziTaskType.Record)) {
                    RazvorotDebugScene("alice-sheet-$mode", recipes, theme)
                }
            }
        }
    }
}
