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
import java.io.File
import com.google.common.truth.Truth.assertThat

/** PR key evidence: ten real surfaces in dawn/night, 20 native PNG total.
 * No taste-based light/curl probes. Extended matrices live in the RC suite. */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],qualifiers="ru-w412dp-h915dp-mdpi",application=MadreApplication::class)
class RazvorotV3RenderTest {
    private fun render(scene:String) {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val recipes=runBlocking{RecipeRepository(context).getRecipes()}
        val module=if(File("app/src/main/AndroidManifest.xml").isFile)File("app") else File(".")
        for(theme in listOf(Atmosphere.DAWN,Atmosphere.NIGHT)) {
        val output=File(module,"build/outputs/roborazzi/key/$scene-${theme.captureName()}.png").absoluteFile
        output.parentFile.mkdirs()
        captureRoboImage(filePath=output.path,roborazziOptions=RoborazziOptions(taskType=RoborazziTaskType.Record)) {
            RazvorotDebugScene(scene,recipes,theme)
        }
        assertThat(output.isFile).isTrue()
        assertThat(output.length()).isGreaterThan(1000L)
        val bitmap=android.graphics.BitmapFactory.decodeFile(output.path)
        assertThat(bitmap.width).isEqualTo(412)
        assertThat(bitmap.height).isEqualTo(915)
        bitmap.recycle()
        }
    }
    @Test fun titleMorning()=render("reader-title-dawn")
    @Test fun text16()=render("reader-text-16")
    @Test fun readingSettings()=render("reading-sheet")
    @Test fun homeMorning()=render("home-dawn")
    @Test fun catalog()=render("catalog")
    @Test fun staticFontsCyrillic()=render("font-compat")
    @Test fun bake()=render("bake")
    @Test fun starterGrowing()=render("starter-growing")
    @Test fun shelf()=render("shelf")
    @Test fun shelfLargeType()=render("shelf-large")
}
