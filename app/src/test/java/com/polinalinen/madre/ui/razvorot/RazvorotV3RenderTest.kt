package com.polinalinen.madre.ui.razvorot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.*
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.repository.RecipeRepository
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** First native F-v3 evidence, NOT a replacement for approved historical goldens.
 * Output lives under build/ and must be reviewed before becoming a pixel baseline. */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],qualifiers="ru-w360dp-h780dp-xhdpi",application=MadreApplication::class)
class RazvorotV3RenderTest {
    private fun render(scene:String) {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val recipes=runBlocking{RecipeRepository(context).getRecipes()}
        captureRoboImage(filePath="build/outputs/roborazzi/f-v3/$scene.png",roborazziOptions=RoborazziOptions(taskType=RoborazziTaskType.Record)) {
            RazvorotDebugScene(scene,recipes)
        }
    }
    @Test fun titleMorning()=render("reader-title-dawn")
    @Test fun titleNight()=render("reader-title-night")
    @Test fun text16()=render("reader-text-16")
    @Test fun text22()=render("reader-text-22")
    @Test fun curlTop()=render("curl-top")
    @Test fun curlMiddle()=render("curl-mid")
    @Test fun curlBottom()=render("curl-bottom")
    @Test fun curlBack()=render("curl-back")
    @Test fun chrome()=render("reader-chrome")
    @Test fun readingSettings()=render("reading-sheet")
    @Test fun homeMorning()=render("home-dawn")
    @Test fun homeNight()=render("home-night")
    @Test fun spine()=render("spine")
    @Test fun bake()=render("bake")
    @Test fun aliceListeningDebug()=render("alice-listening")
    @Test fun aliceReadingDebug()=render("alice-reading")
    @Test fun aliceConfirmDebug()=render("alice-confirm")
    @Test fun aliceNightDebug()=render("alice-night")
    @Test fun starterGrowing()=render("starter-growing")
    @Test fun starterPeak()=render("starter-peak")
    @Test fun starterHungry()=render("starter-hungry")
    @Test fun shelf()=render("shelf")
}
