package com.polinalinen.madre.navigation

import android.app.Application
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.activity.ComponentActivity
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.ui.kit.SettingsMonogram
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], application = Application::class)
class SettingsMonogramAccessibilityTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun settingsEntryHasTheActualActionLabelAndInvokesSettings() {
        var opened = false
        rule.runOnIdle {
            val manager = rule.activity.getSystemService(android.view.accessibility.AccessibilityManager::class.java)
            org.robolectric.Shadows.shadowOf(manager).setEnabledAccessibilityServiceList(listOf(android.accessibilityservice.AccessibilityServiceInfo()))
            org.robolectric.Shadows.shadowOf(manager).setEnabled(true)
            org.robolectric.Shadows.shadowOf(manager).setTouchExplorationEnabled(true)
        }
        rule.setContent { SettingsMonogram(onClick = { opened = true }) }
        rule.onNodeWithContentDescription("Настройки").assertDoesNotExist()
        val entry = rule.onNodeWithTag("settings-monogram")
        val semantics = entry.fetchSemanticsNode()
        assertEquals("Настройки", semantics.config[SemanticsActions.OnClick].label)
        val candidates = listOf(-1) + rule.onAllNodes(hasClickAction(), useUnmergedTree = true)
            .fetchSemanticsNodes().map { it.id }
        val details = StringBuilder("candidates=$candidates semantics=$semantics\n")
        fun exported(view: View): Triple<android.view.accessibility.AccessibilityNodeProvider, Int, AccessibilityNodeInfo>? {
            details.append("view=${view.javaClass.name} provider=${view.accessibilityNodeProvider}\n")
            if (view.javaClass.name == "androidx.compose.ui.platform.AndroidComposeView") {
                val provider = view.accessibilityNodeProvider
                if (provider != null) for (id in candidates) {
                    val node = provider.createAccessibilityNodeInfo(id)
                    details.append("id=$id native=$node\n")
                    if (node == null) continue
                    if (node.actionList.any { it.id == AccessibilityNodeInfo.ACTION_CLICK && it.label?.toString() == "Настройки" })
                        return Triple(provider, id, node)
                }
            }
            if (view is ViewGroup) for (index in 0 until view.childCount) exported(view.getChildAt(index))?.let { return it }
            return null
        }
        rule.runOnIdle {
            val (provider, id, native) = requireNotNull(exported(rule.activity.window.decorView)) { details.toString() }
            assertEquals(rule.activity.packageName, native.packageName?.toString())
            assertNull(native.contentDescription)
            assertEquals("Настройки", native.actionList.single { it.id == AccessibilityNodeInfo.ACTION_CLICK }.label.toString())
            assertTrue(native.isEnabled)
            assertTrue(native.isVisibleToUser)
            // Dispatch through the real Compose Android accessibility provider.
            assertTrue(provider.performAction(id, AccessibilityNodeInfo.ACTION_CLICK, null))
        }
        rule.runOnIdle { assertTrue(opened) }
    }
}
