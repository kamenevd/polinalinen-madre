package com.polinalinen.madre.navigation

import android.app.Application
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.account.FamilyBookState
import com.polinalinen.madre.model.BookDisplaySettings
import com.polinalinen.madre.performance.LocalQaFrameMonitor
import com.polinalinen.madre.performance.QaFrameMonitor
import com.polinalinen.madre.ui.kit.*
import com.polinalinen.madre.ui.screens.SettingsContent
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Real production Settings + Android node export, not a QA APK/device acceptance. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w412dp-h915dp-mdpi", application = Application::class)
class LSettingsQaDriverAccessibilityTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun nativeSettingsRoutesKeepProbeDiagnosticsFpsAndNightReachableWithoutSearch() {
        val preferences = ReadingPreferences(rule.activity, "qa-driver-settings")
        val originalDiagnostic = PaperHapticDiagnostics.state
        lateinit var monitor: QaFrameMonitor
        var back = 0
        rule.runOnIdle {
            val manager = rule.activity.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            org.robolectric.Shadows.shadowOf(manager).setEnabledAccessibilityServiceList(listOf(android.accessibilityservice.AccessibilityServiceInfo()))
            org.robolectric.Shadows.shadowOf(manager).setEnabled(true)
            org.robolectric.Shadows.shadowOf(manager).setTouchExplorationEnabled(true)
            monitor = QaFrameMonitor(rule.activity.window)
            PaperHapticDiagnostics.report(PaperHapticDiagnostic())
        }
        try {
            rule.setContent {
                CompositionLocalProvider(LocalQaFrameMonitor provides monitor) {
                    RazvorotTheme(preferences) {
                        Box {
                            SettingsContent("", {}, { back++ }, {}, "Мадре", {}, 0, 0, 24, {}, true, {}, false, {},
                                null, FamilyBookState.SignedOut, {}, {}, preferences = preferences)
                            if (monitor.enabled) QaFpsOverlay(monitor)
                        }
                    }
                }
            }
            // This is exactly why the former driver By.clazz(EditText) cannot work.
            rule.onAllNodes(hasSetTextAction()).assertCountEquals(0)
            assertFalse(nodes().any { it.third.className?.toString() == "android.widget.EditText" })
            clickAction("Уведомления и вибрация")
            clickAction("Проверить")
            rule.runOnIdle { assertNotEquals("Ещё не проверяли", PaperHapticDiagnostics.state.lastCall) }
            clickAction("Назад", scroll = false)
            clickAction("О приложении")
            clickAction("Для разработчиков")
            rule.onNodeWithText("hasVibrator:", substring = true).performScrollTo().assertIsDisplayed()
            assertTrue(nodes().any { it.third.text?.contains("hasVibrator:") == true })
            rule.onNodeWithContentDescription("Показывать FPS").performScrollTo().assertIsOff()
            val fps = nodes().single { it.third.contentDescription?.toString() == "Показывать FPS" }
            assertTrue(fps.third.isEnabled)
            rule.runOnIdle { assertTrue(fps.first.performAction(fps.second, AccessibilityNodeInfo.ACTION_CLICK, null)) }
            rule.runOnIdle { assertTrue(monitor.enabled) }
            rule.onNodeWithText("JankStats · текущее окно · 1 с").assertIsDisplayed()
            assertTrue(nodes().any { it.third.text?.contains("кадров · UI p95") == true })
            rule.onNodeWithContentDescription("Показывать FPS").performScrollTo().performClick().assertIsOff()
            rule.runOnIdle { assertFalse(monitor.enabled) }
            clickAction("Назад", scroll = false)
            clickAction("Внешний вид")
            clickAction("Тема: Ночь")
            rule.runOnIdle {
                assertEquals(Atmosphere.NIGHT, preferences.atmosphere)
                assertEquals(Atmosphere.NIGHT, ReadingPreferences(rule.activity, "qa-driver-settings").atmosphere)
            }
            clickAction("Назад", scroll = false)
            clickAction("Назад", scroll = false)
            rule.runOnIdle { assertEquals(1, back) }
        } finally {
            rule.runOnIdle { monitor.close(); PaperHapticDiagnostics.report(originalDiagnostic) }
        }
    }

    private fun clickAction(label: String, scroll: Boolean = true) {
        val matcher = SemanticsMatcher("OnClick label=$label") { it.config.getOrNull(SemanticsActions.OnClick)?.label == label }
        if (scroll) rule.onNode(matcher).performScrollTo().assertIsDisplayed()
        val exported = nodes().single { (_, _, node) -> node.isVisibleToUser && node.isEnabled &&
            node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK && it.label?.toString() == label } }
        println("QA_DRIVER_NATIVE label=$label text=${exported.third.text} desc=${exported.third.contentDescription}")
        rule.runOnIdle { assertTrue(exported.first.performAction(exported.second, AccessibilityNodeInfo.ACTION_CLICK, null)) }
        rule.waitForIdle()
    }

    private fun nodes(): List<Triple<android.view.accessibility.AccessibilityNodeProvider, Int, AccessibilityNodeInfo>> {
        val ids = listOf(-1) + rule.onAllNodes(SemanticsMatcher("all") { true }, useUnmergedTree = true).fetchSemanticsNodes().map { it.id }
        val result = mutableListOf<Triple<android.view.accessibility.AccessibilityNodeProvider, Int, AccessibilityNodeInfo>>()
        fun visit(view: View) {
            if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") {
                view.accessibilityNodeProvider?.let { provider ->
                    for (id in ids) provider.createAccessibilityNodeInfo(id)?.let { result.add(Triple(provider, id, it)) }
                }
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) visit(view.getChildAt(index))
        }
        rule.runOnIdle { visit(rule.activity.window.decorView) }
        return result
    }
}
