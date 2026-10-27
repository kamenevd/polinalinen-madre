package com.polinalinen.madre.ui.razvorot

import com.polinalinen.madre.ui.kit.*
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import com.polinalinen.madre.utils.PhotoStore
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

/** PR key evidence: 20 real frames, with changed7.0.15 controls in morning/night.
 * No taste-based light/curl probes. Extended matrices live in the RC suite. */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],qualifiers="ru-w412dp-h915dp-mdpi",application=MadreApplication::class)
class RazvorotV3RenderTest {
    private fun render(scene:String, themes:List<Atmosphere> = listOf(Atmosphere.DAWN,Atmosphere.NIGHT)) {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val recipes=runBlocking{RecipeRepository(context).getRecipes()}
        val photos=if(scene=="bake-finale")nativeSyntheticPhotos(context) else emptyList()
        val module=if(File("app/src/main/AndroidManifest.xml").isFile)File("app") else File(".")
        for(theme in themes) {
        val output=File(module,"build/outputs/roborazzi/key/$scene-${theme.captureName()}.png").absoluteFile
        output.parentFile.mkdirs()
        captureRoboImage(filePath=output.path,roborazziOptions=RoborazziOptions(taskType=RoborazziTaskType.Record)) {
            // Assert actual ownership at the capture host too; never relax production's private-file fence.
            val photoContext=LocalContext.current
            check(photos.all{PhotoStore.resolve(photoContext,it.path).isFile}){"Native photo fixture belongs to a different capture owner"}
            RazvorotDebugScene(scene,recipes,theme,photos)
        }
        assertThat(output.isFile).isTrue()
        assertThat(output.length()).isGreaterThan(1000L)
        val bitmap=android.graphics.BitmapFactory.decodeFile(output.path)
        assertThat(bitmap.width).isEqualTo(412)
        assertThat(bitmap.height).isEqualTo(915)
        bitmap.recycle()
        }
    }
    @Test fun quantitiesAtMiddleValue()=render("sheet-calc-mid")
    @Test fun chapterIngredients()=render("chapter-ingr")
    @Test fun tableOfContents()=render("toc",listOf(Atmosphere.DAWN))
    @Test fun optionalYandexOffer()=render("yandex-offer",listOf(Atmosphere.DAWN))

    @Test fun bakeFinale()=render("bake-finale")
    @Test fun readingSettings()=render("reading-sheet")
    @Test fun readingSettingsMotionOn()=render("reading-sheet-motion-on")

    @Test fun bake()=render("bake")
    @Test fun starterGrowing()=render("starter-growing", listOf(Atmosphere.DAWN))
    @Test fun shelf()=render("shelf", listOf(Atmosphere.DAWN))
    @Test fun aliceUnlinked()=render("alice-sheet-unlinked")
    @Test fun settings()=render("settings", listOf(Atmosphere.DAWN))
    @Test fun settingsSearch()=render("settings-search", listOf(Atmosphere.NIGHT))
}
