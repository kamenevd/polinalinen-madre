package com.polinalinen.madre

import android.view.KeyEvent
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
    private fun hidden() {
        rule.waitUntil(5_000) {
            var visible = true
            rule.activity.runOnUiThread {
                visible = ViewCompat.getRootWindowInsets(rule.activity.window.decorView)
                    ?.isVisible(WindowInsetsCompat.Type.systemBars()) ?: true
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            !visible
        }
    }

    @Test fun fs1ReaderBakeAndFullTimerUseRealHiddenSystemBars() {
        for (name in listOf("reader-chrome", "bake", "fulltimer-start")) { scene(name); hidden() }
    }

    @Test fun fs4ReadingSheetDismissRestoresReaderImmersion() {
        scene("reader-chrome")
        hidden()
        rule.onNodeWithTag("paper-turn").performTouchInput { click(center) }
        rule.onNodeWithTag("reading-settings").performClick()
        rule.onNodeWithText("Как читать").assertIsDisplayed()
        InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
        rule.waitForIdle()
        hidden()
    }

    @Test fun fs5RecreationRebuildsTheSameScopedReaderRequest() {
        scene("reader-chrome"); hidden()
        rule.activityRule.scenario.recreate()
        rule.waitForIdle()
        assertEquals("reader-chrome", rule.activity.scene)
        hidden()
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
        scene("reader-chrome"); hidden()
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
