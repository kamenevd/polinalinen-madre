package com.polinalinen.madre.ui.razvorot

import android.graphics.BitmapFactory
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.*
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
    /** Both approved viewports, in the existing exact-head automatic PR capture. */
    @Test fun bakingPreviousStepFooter() {
        for((width,height) in listOf(360 to 780,412 to 915)) {
            org.robolectric.RuntimeEnvironment.setQualifiers("ru-w${width}dp-h${height}dp-mdpi")
            rule.activityRule.scenario.recreate()
            rule.activityRule.scenario.onActivity{it.atmosphere=Atmosphere.DAWN;it.scene="bake-compact"}
            rule.waitForIdle()
            rule.onNodeWithTag("bake-previous-step").onChildAt(0)
                .assertTextEquals("Назад").assertIsDisplayed().assertIsEnabled()
            rule.onNode(isDialog()).assertDoesNotExist()
            rule.onNodeWithText("Алиса",substring=true).assertIsDisplayed()
            rule.onNodeWithText("Замес с добавками").assertIsDisplayed()
            rule.onAllNodes(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange)).assertCountEquals(0)
            val main=rule.onNodeWithTag("bake-main-actions").fetchSemanticsNode().boundsInRoot
            val back=rule.onNodeWithTag("bake-previous-step").fetchSemanticsNode().boundsInRoot
            val voice=rule.onNodeWithTag("bake-voice-actions").fetchSemanticsNode().boundsInRoot
            val reading=rule.onNodeWithTag("bake-reading").fetchSemanticsNode().boundsInRoot
            val instructions=rule.onNodeWithTag("bake-instructions").fetchSemanticsNode().boundsInRoot
            assertTrue(instructions.bottom<=reading.bottom);assertTrue(reading.bottom<=main.top)
            assertTrue(back.top>=main.bottom);assertTrue(voice.top>=back.bottom)
            val layouts=mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            rule.onNodeWithTag("bake-instructions").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult){it(layouts)}
            assertFalse(layouts.single().hasVisualOverflow)
            val module=if(File("app/src/main/AndroidManifest.xml").isFile)File("app") else File(".")
            val output=File(module,"build/outputs/roborazzi/bake-back/bake-back-dawn-${width}x${height}.png")
            output.parentFile!!.mkdirs()
            captureScreenRoboImage(filePath=output.absolutePath,roborazziOptions=RoborazziOptions(taskType=RoborazziTaskType.Record))
            val image=requireNotNull(BitmapFactory.decodeFile(output.absolutePath))
            assertEquals(width,image.width);assertEquals(height,image.height);image.recycle()
        }
    }

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
