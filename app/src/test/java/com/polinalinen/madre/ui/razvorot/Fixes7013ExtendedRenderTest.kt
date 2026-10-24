package com.polinalinen.madre.ui.razvorot

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4

import com.github.takahirom.roborazzi.*
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.repository.RecipeRepository
import com.polinalinen.madre.ui.screens.GalleryPhoto
import com.polinalinen.madre.ui.kit.Atmosphere
import com.polinalinen.madre.utils.PhotoStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** Test-sandbox files from bundled recipe art, never actual user photos or Room records. */
internal fun nativeSyntheticPhotos(context:Context):List<GalleryPhoto> = runBlocking {
    RecipeRepository(context).getRecipes().take(4).mapIndexed { index,recipe ->
        val bitmap=requireNotNull(BitmapFactory.decodeResource(context.resources,recipePhoto(recipe.id)))
        val path=try {requireNotNull(PhotoStore.commitBitmap(context,bitmap,PhotoStore.PhotoKind.BAKE,9000L+index))} finally {bitmap.recycle()}

        GalleryPhoto("synthetic-$index",path,1792731600000L-index,"Тестовый снимок ${index+1}")
    }
}

/** Explicit manual-only frozen-head capture, not a PR pixel gate or live auth/device acceptance. */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],qualifiers="ru-w412dp-h915dp-mdpi",application=MadreApplication::class)
class Fixes7013ExtendedRenderTest {
    @get:Rule val rule=createAndroidComposeRule<RazvorotSceneActivity>()
    @Test fun captureRequestedStatesAtOneHead() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val photos=nativeSyntheticPhotos(context)
        assertEquals(4,photos.size)
        val module=if(File("app/src/main/AndroidManifest.xml").isFile)File("app") else File(".")
        val directory=File(module,"build/outputs/roborazzi/fixes-7014").apply{mkdirs()}
        val cases=buildList<Pair<String,Atmosphere>> {
            // The original 20 frames, captured by the same actual-window compositor as the extras.
            for(scene in listOf("chapter-title","toc","oven","start-later","starter-growing","shelf"))add(scene to Atmosphere.DAWN)
            for(scene in listOf("chapter-ingr","bake","bake-finale","yandex-offer"))for(theme in listOf(Atmosphere.DAWN,Atmosphere.NIGHT))add(scene to theme)
            for(theme in listOf(Atmosphere.DAWN,Atmosphere.DAY,Atmosphere.DUSK,Atmosphere.NIGHT))add("reading-sheet" to theme)
            add("alice-sheet-unlinked" to Atmosphere.NIGHT);add("alice-yandex-consent" to Atmosphere.NIGHT)
            for(scene in listOf("toc-search","toc-search-flour","toc-empty","chapter-end-edge","chapter-active","sheet-calc","sheet-calc-flour","sheet-calc-pct",
                "sheet-start-now","oven-empty","oven-planned","oven-overdue","bake-two-chips","bake-more-steps","bake-undo-snack","bake-fulltimer",
                "bake-finale-photo","bake-finale-noaccount","yandex-offer-busy","yandex-offer-error","yandex-offer-error-network","yandex-offer-error-yandex","yandex-offer-error-server","yandex-offer-offline",
                "settings-account-out","settings-account-in","account-sheet","signout-dialog","station-timer","toc-greeting"))add(scene to Atmosphere.DAWN)
            for(scene in listOf("alice-connect-0","alice-connect-1","alice-connect-2"))for(theme in listOf(Atmosphere.DAWN,Atmosphere.NIGHT))add(scene to theme)
            add("settings-in" to Atmosphere.NIGHT);add("alice-linked" to Atmosphere.NIGHT)
        }
        val failures=mutableListOf<String>()
        for((name,theme) in cases)try {
            val scene=when(name){"toc-search","toc-search-flour","toc-empty"->"toc";"sheet-calc-flour","sheet-calc-pct"->"sheet-calc";"bake-more-steps"->"bake-more";else->name}
            // Dispose each previous modal and its saved UI state before opening the next scene.
            rule.activityRule.scenario.onActivity{it.scene="fixture-reset-$name";it.atmosphere=theme;it.recentPhotos=photos}
            rule.waitForIdle()
            rule.activityRule.scenario.onActivity{it.scene=scene}
            rule.waitForIdle()
            when(name) {
                "toc-search"->rule.onNodeWithTag("toc-search").performTextInput("рж")
                "toc-search-flour"->rule.onNodeWithTag("toc-search").performTextInput("мука")
                "toc-empty"->rule.onNodeWithTag("toc-search").performTextInput("зефир")
                "sheet-calc-flour"->rule.onNodeWithText("По муке").performClick()
                "sheet-calc-pct"->rule.onNodeWithText("% пекаря").performScrollTo().performClick()
                "chapter-end-edge"->{
                    val next=rule.onNodeWithTag("paper-turn").fetchSemanticsNode().config[SemanticsActions.CustomActions]
                        .first{it.label=="Следующая страница"}
                    rule.runOnIdle{next.action()}
                    rule.onNodeWithTag("chapter-edge").assertExists()
                }
            }
            rule.waitForIdle()
            val output=File(directory,"$name-${theme.captureName()}.png")
            captureScreenRoboImage(filePath=output.absolutePath,roborazziOptions=RoborazziOptions(taskType=RoborazziTaskType.Record))
            val bitmap=requireNotNull(BitmapFactory.decodeFile(output.absolutePath))
            assertEquals("$name width",412,bitmap.width);assertEquals("$name height",915,bitmap.height)
            bitmap.recycle()
        } catch(failure:Throwable) {failures.add("$name/${theme.captureName()}: ${failure::class.simpleName}: ${failure.message}")}
        File(directory,"README.txt").writeText("FIXES7.0.14 native Android/Compose window-composite render; 412x915, mdpi, API35.\n"+
            "All profile/voice/photo/bake states are explicit synthetic fixtures. No SDK login, backend grant, Station or physical-device acceptance.\n"+
            "Clock: 2026-10-24T13:40:00Z Europe/Moscow. Missing states fail the capture; no screenshot rescaling/cropping.\n"+
            "Requested ${cases.size} frames; failures ${failures.size}.\n"+failures.joinToString("\n"))
        assertTrue(failures.joinToString("\n"),failures.isEmpty())
        assertEquals(cases.size,directory.listFiles{file->file.extension=="png"}!!.size)
    }
}
