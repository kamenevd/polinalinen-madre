package com.polinalinen.madre

import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.services.storage.TestStorage
import com.polinalinen.madre.ui.kit.Atmosphere
import com.polinalinen.madre.ui.razvorot.RazvorotSceneActivity
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** NEW required owner15 CI checks, distinct from legacy optional fullscreen suites.
 * Real physical cutout insets/actual touches; AVD is not a Huawei feeling/gesture acceptance. */
class Huawei7015AcceptanceTest {
    @get:Rule val rule=createAndroidComposeRule<RazvorotSceneActivity>()
    private fun scene(name:String,night:Boolean=false) {
        rule.runOnIdle{rule.activity.scene=name;rule.activity.atmosphere=if(night)Atmosphere.NIGHT else Atmosphere.DAWN}
        rule.waitForIdle()
        rule.onNodeWithTag("scene-$name").assertExists()
        if(name=="yandex-offer")rule.onNodeWithText("НЕОБЯЗАТЕЛЬНО · МОЖНО ПОЗЖЕ").assertIsDisplayed()
    }
    private fun evidence(name:String)=TestStorage().openOutputFile("screenshots/huawei15/$name")
    private fun shot(name:String) {
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        fun nodes(root:android.view.accessibility.AccessibilityNodeInfo):List<android.view.accessibility.AccessibilityNodeInfo> =
            listOf(root)+(0 until root.childCount).flatMap{index->root.getChild(index)?.let{nodes(it)}?:emptyList()}
        val initial=automation.rootInActiveWindow
        val texts=initial?.let{nodes(it)}?.mapNotNull{it.text?.toString()}.orEmpty()
        // Close only the known CI Launcher ANR. An app ANR is a failure, never dismissed or cropped.
        if(texts.any{it.contains("Pixel Launcher") || it.contains("Nexus Launcher")}) {
            initial?.findAccessibilityNodeInfosByViewId("android:id/aerr_close")?.firstOrNull()
                ?.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK)
        }
        rule.waitForIdle()
        rule.waitUntil(10_000){automation.rootInActiveWindow?.packageName?.toString()==rule.activity.packageName}
        assertEquals(rule.activity.packageName,automation.rootInActiveWindow?.packageName?.toString())
        // Choreographer callbacks run BEFORE drawing and can capture the previous scene.
        // Wait for accessibility to settle, then an actual RenderThread frame COMMIT.
        automation.waitForIdle(500,10_000)
        val frame=java.util.concurrent.CountDownLatch(1)
        rule.activity.runOnUiThread{
            val view=rule.activity.window.decorView
            assertTrue("Hardware-backed frame commit required",view.isHardwareAccelerated)
            view.viewTreeObserver.registerFrameCommitCallback{frame.countDown()}
            view.requestLayout();view.postInvalidateOnAnimation()
        }
        assertTrue("Actual frame was not committed",frame.await(10,java.util.concurrent.TimeUnit.SECONDS))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        automation.waitForIdle(500,10_000)
        assertEquals(rule.activity.packageName,automation.rootInActiveWindow?.packageName?.toString())
        evidence("$name-window.txt").bufferedWriter().use{it.write("activePackage=${automation.rootInActiveWindow?.packageName}\nscene=${rule.activity.scene}\nFull unmodified UiAutomation frame; actual RenderThread commit and accessibility idle awaited.\n")}
        val bitmap=requireNotNull(automation.takeScreenshot())
        evidence("$name.png").use{assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))};bitmap.recycle()
    }
    private fun page()=rule.onNodeWithTag("paper-turn").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
        .substringAfter("Страница ").substringBefore(' ').toInt()
    @Test fun realNotchNeverCoversTextInShelfStarterSettingsOrOnboarding() {
        for(name in listOf("shelf-night","starter-growing","settings","yandex-offer")) {
            scene(name,true)
            var top=0
            rule.runOnIdle{
                val insets=requireNotNull(ViewCompat.getRootWindowInsets(rule.activity.window.decorView))
                val cutout=requireNotNull(insets.displayCutout){"Actual notch overlay required"}
                top=insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.displayCutout()).top
                assertTrue(top>0);assertTrue(cutout.boundingRects.isNotEmpty())
                evidence("$name-insets.txt").bufferedWriter().use{it.write("bounds=${cutout.boundingRects}\nsafeTop=$top\nbarsVisible=${insets.isVisible(WindowInsetsCompat.Type.systemBars())}\n")}
            }
            val texts=rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),useUnmergedTree=true).fetchSemanticsNodes()
                .filter{it.boundsInRoot.width>0 && it.boundsInRoot.height>0}
            assertTrue("No real text observed on $name",texts.isNotEmpty())
            for(node in texts)assertTrue("$name text under actual notch: ${node.config[SemanticsProperties.Text]} ${node.boundsInRoot}",node.boundsInRoot.top>=top)
            shot("cutout-$name")
        }
    }
    @Test fun actualBackwardTapAndSwipeWorkAtEndAndClampFirstInEveryChapter() {
        val recipes=kotlinx.coroutines.runBlocking{com.polinalinen.madre.data.repository.RecipeRepository(rule.activity).getRecipes()}
        for(recipe in recipes) {
            scene("chapter-end-edge:${recipe.id}")
            rule.onNode(hasText(recipe.name) and hasAnyAncestor(hasTestTag("chapter-header"))).assertIsDisplayed()
            rule.onNodeWithContentDescription("Пометки этой главы").assertDoesNotExist()
            val last=page();assertTrue(last>2)
            rule.onNodeWithTag("paper-turn").performTouchInput{click(androidx.compose.ui.geometry.Offset(width*.12f,center.y))}
            rule.waitUntil(5_000){page()==last-1}
            rule.onNodeWithTag("paper-turn").performTouchInput{swipe(androidx.compose.ui.geometry.Offset(width*.2f,center.y),androidx.compose.ui.geometry.Offset(width*.85f,center.y),150)}
            rule.waitUntil(5_000){page()==last-2}
            scene("chapter-title:${recipe.id}")
            rule.onNode(hasText(recipe.name) and hasAnyAncestor(hasTestTag("chapter-header"))).assertIsDisplayed()
            assertEquals(1,page())
            rule.onNodeWithTag("paper-turn").performTouchInput{click(androidx.compose.ui.geometry.Offset(width*.12f,center.y))}
            rule.waitForIdle();assertEquals(1,page())
            rule.onNodeWithTag("paper-turn").performTouchInput{swipeRight(durationMillis=150)}
            rule.waitForIdle();assertEquals(1,page())
        }
        shot("backward-all-chapters-first-clamped")
    }
    @Test fun nightChapterBottomAndCompleteOrderedSettingsCapture() {
        scene("chapter-ingr",true);shot("night-chapter")
        scene("toc",true);rule.onNodeWithTag("spine-navigation").assertIsDisplayed();shot("night-bottom-navigation")
        // Changed production image loading/lazy rows also get real pixels, not a mock.
        scene("oven",true);Thread.sleep(700);rule.waitForIdle();shot("night-oven-sized-images")
        scene("settings",true)
        val list=rule.onNodeWithTag("paper-list")
        shot("settings-full-01")
        fun lastVisible()=runCatching{rule.onNodeWithText(com.polinalinen.madre.BuildConfig.VERSION_NAME).assertIsDisplayed()}.isSuccess
        var index=1
        while(!lastVisible() && index<12) {
            val before=list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
            list.performSemanticsAction(SemanticsActions.ScrollBy){it(0f,rule.activity.resources.displayMetrics.heightPixels*.7f)}
            rule.waitUntil(10_000){list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()>before}
            rule.waitForIdle()
            index++;shot("settings-full-${index.toString().padStart(2,'0')}")
        }
        assertTrue("Settings sequence incomplete",lastVisible())
        rule.onNodeWithText(com.polinalinen.madre.BuildConfig.VERSION_NAME).assertIsDisplayed()
        evidence("settings-full-sequence.txt").bufferedWriter().use{it.write("Complete ordered real screen captures: $index\n")}
    }
}
