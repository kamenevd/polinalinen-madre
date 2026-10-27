package com.polinalinen.madre

import android.view.KeyEvent
import android.os.ParcelFileDescriptor
import android.accessibilityservice.AccessibilityService
import android.os.SystemClock
import android.view.MotionEvent
import android.view.InputDevice
import android.graphics.Bitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.lifecycle.Lifecycle
import androidx.test.services.storage.TestStorage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.polinalinen.madre.ui.razvorot.RazvorotSceneActivity
import com.polinalinen.madre.ui.kit.Atmosphere
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Manual/nightly/RC only. Production and this activity share ImmersiveController. */
class FullscreenInstrumentedTest {
    @get:Rule val rule = createAndroidComposeRule<RazvorotSceneActivity>()

    private fun scene(name: String) {
        rule.runOnIdle { rule.activity.scene = name }
        rule.waitForIdle()
    }
    private fun hidden(timeout:Long=5_000) {
        rule.waitUntil(timeout) {
            var visible = true
            rule.activity.runOnUiThread {
                visible = ViewCompat.getRootWindowInsets(rule.activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.systemBars()) ?: true
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            !visible
        }
    }

    private fun hideReaderChrome() {
        if(rule.onAllNodesWithContentDescription("В оглавление").fetchSemanticsNodes().isNotEmpty())
            rule.onNodeWithTag("paper-turn").performTouchInput{click(center)}
        hidden()
    }
    private fun page()=rule.onNodeWithTag("paper-turn").fetchSemanticsNode().config[SemanticsProperties.StateDescription]
    private fun secondPage() {
        val action=rule.onNodeWithTag("paper-turn").fetchSemanticsNode().config[SemanticsActions.CustomActions].first{it.label=="Следующая страница"}
        rule.runOnIdle{assertTrue(action.action())};rule.waitForIdle()
    }
    // TestStorage survives Gradle's target-APK cleanup; app external files do not.
    private fun evidence(name:String)=TestStorage().openOutputFile("screenshots/fs-evidence/$name")
    private fun shot(name:String):Bitmap {
        val bitmap=requireNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        evidence("$name.png").use{assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,it))}
        return bitmap
    }
    private fun verifyRealCutout(name:String) {
        rule.runOnIdle {
            val insets=requireNotNull(ViewCompat.getRootWindowInsets(rule.activity.window.decorView))
            val cutout=requireNotNull(insets.displayCutout){"CI overlay must expose actual cutout insets"}
            assertTrue("Actual cutout bounds missing",cutout.boundingRects.isNotEmpty())
            val safe=insets.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.displayCutout())
            assertTrue("Actual cutout safe inset missing",safe.top>0 || safe.left>0 || safe.right>0)
            evidence("$name-insets.txt").bufferedWriter().use{it.write("boundingRects=${cutout.boundingRects}\nsafeInsets=$safe\nsystemBarsVisible=${insets.isVisible(WindowInsetsCompat.Type.systemBars())}\n")}
        }
    }

    @Test fun fs1ReaderBakeAndFullTimerUseRealHiddenSystemBars() {
        for (name in listOf("reader-chrome", "bake", "fulltimer-start", "photo-viewer")) {
            scene(name)
            if(name=="reader-chrome")hideReaderChrome() else hidden()
            verifyRealCutout(name)
            shot("fs1-$name").recycle()
        }
    }

    @Test fun fs4ReadingSheetDismissRestoresReaderImmersion() {
        scene("reader-chrome")
        rule.onNodeWithText("Aa").performClick()
        rule.onNodeWithText("Как читать").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.waitForIdle()
        hidden(300)
        for(name in listOf("fullscreen-dialog","fullscreen-photo")) {
            scene(name)
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            rule.waitForIdle();hidden(300)
        }
    }

    @Test fun fs5RecreationRebuildsTheSameScopedReaderRequest() {
        scene("reader-chrome");secondPage();hideReaderChrome()
        val before=page()
        val activity=rule.activity
        val task=activity.taskId
        assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        rule.waitUntil(5_000){activity.lifecycle.currentState!=Lifecycle.State.RESUMED}
        // Resume the SAME actual task, like selecting it in Recents. Neither an app-context
        // background launch nor ActivityScenario.moveToState brings a HOME-stopped task forward.
        val command=InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("am task focus $task")
        ParcelFileDescriptor.AutoCloseInputStream(command).bufferedReader().use{it.readText()}
        rule.waitUntil(5_000){activity.lifecycle.currentState==Lifecycle.State.RESUMED && activity.hasWindowFocus()}
        rule.waitForIdle();hidden();assertEquals(before,page())
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        assertEquals("reader-chrome", rule.activity.scene)
        assertEquals(before,page())
        hidden()
    }

    @Test fun fs6RealTopEdgeSwipeShowsTransientBarsThenRehidesWithoutChangingPage() {
        scene("reader-chrome");secondPage();hideReaderChrome()
        verifyRealCutout("fs6")
        val beforePage=page()
        val beforeBounds=rule.onNodeWithTag("paper-turn").fetchSemanticsNode().boundsInRoot
        val before=shot("fs6-before")
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        val down=SystemClock.uptimeMillis()
        val x=before.width*.25f
        fun inject(action:Int,y:Float) {
            val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0).apply{source=InputDevice.SOURCE_TOUCHSCREEN}
            try {assertTrue(automation.injectInputEvent(event,true))} finally {event.recycle()}
        }
        inject(MotionEvent.ACTION_DOWN,0f)
        for(i in 1..12){SystemClock.sleep(16);inject(MotionEvent.ACTION_MOVE,i*10f)}
        inject(MotionEvent.ACTION_UP,120f)
        SystemClock.sleep(200)
        val shown=shot("fs6-transient")
        fun topPixels(bitmap:Bitmap):IntArray {
            val height=(80*rule.activity.resources.displayMetrics.density).toInt().coerceAtMost(bitmap.height)
            return IntArray(bitmap.width*height).also{bitmap.getPixels(it,0,bitmap.width,0,0,bitmap.width,height)}
        }
        // Application insets stay hidden for transient overlays: observe actual system pixels too.
        assertFalse("Real edge swipe did not reveal system-bar pixels",topPixels(before).contentEquals(topPixels(shown)))
        val deadline=SystemClock.uptimeMillis()+8_000
        var restored=false
        while(SystemClock.uptimeMillis()<deadline && !restored) {
            SystemClock.sleep(250)
            val current=requireNotNull(automation.takeScreenshot())
            restored=topPixels(before).contentEquals(topPixels(current));current.recycle()
        }
        assertTrue("Transient bars did not re-hide to the original paper",restored)
        hidden();rule.waitForIdle()
        assertEquals(beforePage,page())
        assertEquals(beforeBounds,rule.onNodeWithTag("paper-turn").fetchSemanticsNode().boundsInRoot)
        val after=shot("fs6-after")
        assertTrue("Reader frame changed after transient bars",before.sameAs(after))
        before.recycle();shown.recycle();after.recycle()
    }

    @Test fun fs7WindowContrastAndPaperIconPolarityAreExplicit() {
        scene("settings")
        for (theme in listOf(Atmosphere.DAWN, Atmosphere.DAY, Atmosphere.DUSK, Atmosphere.NIGHT)) {
            rule.runOnIdle { rule.activity.atmosphere = theme }
            rule.waitForIdle()
            rule.runOnIdle {
                val window = rule.activity.window
                assertFalse(window.isNavigationBarContrastEnforced)
                assertFalse(window.isStatusBarContrastEnforced)
                val controller = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
                assertEquals(theme != Atmosphere.NIGHT, controller.isAppearanceLightStatusBars)
                assertEquals(theme != Atmosphere.NIGHT, controller.isAppearanceLightNavigationBars)
            }
        }
    }

    @Test fun fs8NoImmersiveEducationOverlayIsVisible() {
        scene("reader-chrome");hideReaderChrome()
        val root = InstrumentationRegistry.getInstrumentation().uiAutomation.rootInActiveWindow
        fun check(node: android.view.accessibility.AccessibilityNodeInfo?) {
            if (node == null) return
            val text = node.text?.toString().orEmpty()
            assertFalse(text.contains("Viewing full screen", true))
            assertFalse(text.contains("Просмотр в полноэкранном режиме", true))
            for (i in 0 until node.childCount) check(node.getChild(i))
        }
        check(root)
    }
}
