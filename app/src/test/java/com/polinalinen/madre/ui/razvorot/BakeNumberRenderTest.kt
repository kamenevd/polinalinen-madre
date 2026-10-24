package com.polinalinen.madre.ui.razvorot

import android.graphics.BitmapFactory
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.*
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.ui.kit.Atmosphere
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Additional B3/B5 full-window evidence, not replacement baselines or pixel taste probes. */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],qualifiers="ru-w412dp-h915dp-mdpi",application=MadreApplication::class)
class BakeNumberRenderTest {
    @get:Rule val rule=createAndroidComposeRule<RazvorotSceneActivity>()
    @Test fun numberedSurfacesUseProductionRenderers() {
        val module=if(File("app/src/main/AndroidManifest.xml").isFile)File("app") else File(".")
        val directory=File(module,"build/outputs/roborazzi/bake-numbers").apply{mkdirs()}
        val cases=buildList<Pair<String,Atmosphere>> {
            for(scene in listOf("oven-numbers","bake-number"))for(theme in listOf(Atmosphere.DAWN,Atmosphere.NIGHT))add(scene to theme)
            for(scene in listOf("sheet-start-number","bake-fulltimer-number","bake-finale-number","chapter-active-number","alice-linked-number","station-number","oven-nine-snack"))add(scene to Atmosphere.DAWN)
        }
        for((scene,theme) in cases) {
            rule.activityRule.scenario.onActivity{it.scene="fixture-reset-$scene";it.atmosphere=theme}
            rule.waitForIdle()
            rule.activityRule.scenario.onActivity{it.scene=scene}
            rule.waitForIdle()
            val output=File(directory,"$scene-${theme.captureName()}.png")
            captureScreenRoboImage(filePath=output.absolutePath,roborazziOptions=RoborazziOptions(taskType=RoborazziTaskType.Record))
            val image=requireNotNull(BitmapFactory.decodeFile(output.absolutePath))
            assertEquals(412,image.width);assertEquals(915,image.height);image.recycle()
        }
        assertEquals(cases.size,directory.listFiles{file->file.extension=="png"}!!.size)
        File(directory,"README.txt").writeText("7.0.16 B3/B5, synthetic bake ids811/872, displayed numbers1/2. Full native412x915 windows, no crop/repaint or replacement of core20. ${cases.size} PNG.\n")
    }
}
