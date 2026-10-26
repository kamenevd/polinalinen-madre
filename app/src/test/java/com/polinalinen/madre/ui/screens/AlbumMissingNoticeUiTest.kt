package com.polinalinen.madre.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.ui.kit.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w320dp-h640dp-mdpi", application = MadreApplication::class)
class AlbumMissingNoticeUiTest {
    @get:Rule val rule = createComposeRule()
    private val photos = listOf(GalleryPhoto("missing", "absent-notice.jpg", 1_000, "Нет файла", GalleryPhotoSource.STARTER))
    private fun tap() {
        // bookAction keeps its real wall-clock TapGate; virtual frame time cannot reopen it.
        Thread.sleep(com.polinalinen.madre.ui.components.TapGate.DEFAULT_WINDOW_MILLIS + 20)
        rule.onNodeWithTag("gallery-photo-missing").performClick()
    }
    private fun notice() = rule.onNodeWithText("Файл не найден")

    @Test fun realSnackbarFitsAt320dpBothScalesAndFourAtmospheresWithoutBlockingBack() {
        val atmosphere = mutableStateOf(Atmosphere.DAY)
        val scale = mutableStateOf(1f)
        var backs = 0
        rule.setContent {
            RazvorotTheme(force = atmosphere.value) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale.value)) {
                    PhotoGalleryScreen(photos, { backs++ })
                }
            }
        }
        assertEquals(4, Atmosphere.entries.count { it != Atmosphere.AUTO })
        val overflows = mutableListOf<String>()
        for (theme in Atmosphere.entries.filter { it != Atmosphere.AUTO }) for (font in listOf(1f, 2f)) {
            rule.runOnIdle { atmosphere.value = theme; scale.value = font }
            tap()
            val layouts = mutableListOf<TextLayoutResult>()
            notice().assertIsDisplayed().performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            val layout = layouts.single()
            val diagnostic = "$theme scale=$font size=${layout.size} lines=${layout.lineCount} width=${layout.didOverflowWidth} height=${layout.didOverflowHeight} constraints=${layout.layoutInput.constraints} intrinsic=${layout.multiParagraph.intrinsics.maxIntrinsicWidth}"
            println(diagnostic)
            if (layout.hasVisualOverflow) overflows.add(diagnostic)
            assertEquals(PaperType.body.fontFamily, layout.layoutInput.style.fontFamily)
            assertEquals(PaperType.body.fontSize, layout.layoutInput.style.fontSize)
            assertEquals(font, layout.layoutInput.density.fontScale, 0f)
            val bounds = notice().fetchSemanticsNode().boundsInRoot
            val root = rule.onRoot().fetchSemanticsNode().boundsInRoot
            assertTrue("bottom visible: $bounds in $root", bounds.bottom <= root.bottom && bounds.top > root.center.y)
            rule.onNodeWithText("Закрыть").assertDoesNotExist()
            rule.onNodeWithText("← Дневник закваски").performClick()
            rule.runOnIdle { assertEquals(++expectedBacks, backs) }
        }
        assertTrue(overflows.joinToString("\n"), overflows.isEmpty())
    }
    private var expectedBacks = 0

    @Test fun repeatedTapsCoalesceAndDoNotKeepAQueuedNoticeAfterTheShortTimeout() {
        rule.setContent { RazvorotTheme(force = Atmosphere.DAY) { PhotoGalleryScreen(photos, {}) } }
        tap(); notice().assertIsDisplayed()
        repeat(12) { tap() }
        rule.onAllNodesWithText("Файл не найден").assertCountEquals(1)
        rule.mainClock.advanceTimeBy(6_000)
        notice().assertDoesNotExist()
        rule.mainClock.advanceTimeBy(12_000)
        notice().assertDoesNotExist()
        tap(); notice().assertIsDisplayed()
        rule.onNodeWithContentDescription("Источник фото, Все 1").assertIsSelected()
        rule.onNodeWithContentDescription("Источник фото, Закваска 1").assertExists()
        rule.onNodeWithTag("gallery-photo-missing").assertExists()
    }

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    @Test fun stopDisposalAndSavedStateRestoreDoNotResurrectTheTransientMessage() {
        val owner = Owner()
        val visible = mutableStateOf(true)
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            RazvorotTheme(force = Atmosphere.DAY) {
                CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                    if (visible.value) PhotoGalleryScreen(photos, {})
                }
            }
        }
        tap(); notice().assertIsDisplayed()
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.CREATED }
        rule.mainClock.advanceTimeBy(500)
        notice().assertDoesNotExist()
        rule.runOnIdle { owner.registry.currentState = Lifecycle.State.RESUMED }
        notice().assertDoesNotExist()
        tap(); notice().assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        notice().assertDoesNotExist()
        tap(); notice().assertIsDisplayed()
        rule.runOnIdle { visible.value = false }
        rule.runOnIdle { visible.value = true }
        rule.mainClock.advanceTimeBy(8_000)
        notice().assertDoesNotExist()
        tap(); notice().assertIsDisplayed()
    }
}
