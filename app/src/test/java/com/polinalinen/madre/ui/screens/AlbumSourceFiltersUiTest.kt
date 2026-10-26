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
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.ui.kit.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w320dp-h640dp-mdpi", application = MadreApplication::class)
class AlbumSourceFiltersUiTest {
    @get:Rule val rule = createComposeRule()

    private fun filter(label: String) = rule.onNodeWithContentDescription("Источник фото, $label")

    @Test fun explicitSourcesIgnoreMisleadingIdsPathsCaptionsAndLegacyStaysUnclassified() {
        val photos = mutableStateOf(listOf(
            GalleryPhoto("bake-pretender", "bake/missing.jpg", 20, "Выпечка", GalleryPhotoSource.STARTER),
            GalleryPhoto("feeding-pretender", "feeding/missing.jpg", 20, "Кормление", GalleryPhotoSource.BAKING),
            GalleryPhoto("legacy", "bake/missing.jpg", 10, "Кормление"),
            GalleryPhoto("blank", "  ", 30, "Выпечка", GalleryPhotoSource.BAKING)))
        val restoration = StateRestorationTester(rule)
        restoration.setContent { RazvorotTheme(force = Atmosphere.DAY) { PhotoGalleryScreen(photos.value, {}) } }
        filter("Все 3").assertIsSelected()
        val starter = rule.onNodeWithTag("gallery-photo-bake-pretender").fetchSemanticsNode().boundsInRoot
        val bake = rule.onNodeWithTag("gallery-photo-feeding-pretender").fetchSemanticsNode().boundsInRoot
        assertTrue("Equal timestamps retain producer order", starter.top < bake.top || starter.left < bake.left)
        filter("Закваска 1").performClick().assertIsSelected()
        rule.onNodeWithTag("gallery-photo-bake-pretender").assertIsDisplayed()
        rule.onNodeWithTag("gallery-photo-feeding-pretender").assertDoesNotExist()
        rule.onNodeWithTag("gallery-photo-legacy").assertDoesNotExist()
        filter("Выпечка 1").performClick().assertIsSelected()
        rule.onNodeWithTag("gallery-photo-feeding-pretender").assertIsDisplayed()
        rule.onNodeWithTag("gallery-photo-bake-pretender").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        filter("Выпечка 1").assertIsSelected()
        rule.runOnIdle { photos.value = photos.value.filter { it.source != GalleryPhotoSource.BAKING } }
        filter("Выпечка 0").assertIsSelected()
        rule.onNodeWithText("В этом разделе пока нет фотографий.").assertIsDisplayed()
        filter("Все 2").performClick()
        rule.onNodeWithTag("gallery-photo-legacy").assertExists()
        assertNull(GalleryPhoto("old", "x", 0, "Выпечка").source)
    }

    @Test fun actualHeadingAndSegmentLabelsFit320dpAtBothFontScalesInEveryAtmosphere() {
        val atmosphere = mutableStateOf(Atmosphere.DAY)
        val scale = mutableStateOf(1f)
        val photos = (1..48).map { GalleryPhoto("$it", "missing-$it", it.toLong(), "Фото",
            if (it <= 31) GalleryPhotoSource.STARTER else GalleryPhotoSource.BAKING) }
        rule.setContent {
            RazvorotTheme(force = atmosphere.value) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale.value)) {
                    PhotoGalleryScreen(photos, {})
                }
            }
        }
        for (theme in Atmosphere.entries.filter { it != Atmosphere.AUTO }) for (font in listOf(1f, 2f)) {
            rule.runOnIdle { atmosphere.value = theme; scale.value = font }
            for (label in listOf("Фото", "Все 48", "Закваска 31", "Выпечка 17")) {
                val layouts = mutableListOf<TextLayoutResult>()
                rule.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
                val layout = layouts.single()
                val diagnostic = "$theme scale=$font label=$label size=${layout.size} lines=${layout.lineCount} width=${layout.didOverflowWidth} height=${layout.didOverflowHeight}"
                println(diagnostic)
                assertFalse(diagnostic, layout.hasVisualOverflow)
                assertEquals(font, layout.layoutInput.density.fontScale, 0f)
                assertEquals(if (label == "Фото") PaperType.h1.fontSize else 12.sp, layout.layoutInput.style.fontSize)
            }
            filter("Закваска 31").performClick().assertIsSelected()
            filter("Выпечка 17").performClick().assertIsSelected()
            filter("Все 48").performClick().assertIsSelected()
        }
    }
}
