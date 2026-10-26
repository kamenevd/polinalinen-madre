package com.polinalinen.madre.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.sourdough.GrowthPhase
import com.polinalinen.madre.sourdough.profileForInterval
import com.polinalinen.madre.ui.kit.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w320dp-h640dp-mdpi", application = MadreApplication::class)
class DiaryActionsUiTest {
    @get:Rule val rule = createComposeRule()

    private fun verify(scale: Float, initialCount: Int) {
        val count = mutableStateOf(initialCount)
        var feeds = 0
        var galleries = 0
        rule.setContent {
            RazvorotTheme(force = Atmosphere.DAY) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                    StarterDiaryScreen(1, GrowthPhase.EMPTY, profileForInterval(24), emptyList(), emptyList(),
                        {}, { feeds++ }, { galleries++ }, nowMillis = 0, photoCount = count.value)
                }
            }
        }
        fun check(label: String, foreground: androidx.compose.ui.graphics.Color) {
            rule.onNodeWithTag("diary-list").performScrollToNode(hasText(label))
            val node = rule.onNodeWithText(label).performScrollTo().assertIsDisplayed().assertIsEnabled()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            val click = node.fetchSemanticsNode().config[SemanticsActions.OnClick]
            assertThat(click.label).isEqualTo(label)
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertThat(bounds.width).isAtLeast(50f)
            assertThat(bounds.height).isAtLeast(50f)
            val layouts = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(label, useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertThat(it(layouts)).isTrue() }
            val layout = layouts.single()
            assertWithMessage("$label scale=$scale size=${layout.size} bounds=$bounds constraints=${layout.layoutInput.constraints} " +
                "intrinsic=${layout.multiParagraph.intrinsics.maxIntrinsicWidth} lines=${layout.lineCount} " +
                "width=${layout.didOverflowWidth} height=${layout.didOverflowHeight}")
                .that(layout.hasVisualOverflow).isFalse()
            assertThat(layout.lineCount).isEqualTo(1)
            assertThat(layout.layoutInput.style.fontSize).isEqualTo(PaperType.ui.fontSize)
            assertThat(layout.layoutInput.style.fontWeight).isEqualTo(FontWeight.Bold)
            assertThat(layout.layoutInput.style.color).isEqualTo(foreground)
            node.performClick()
        }
        check("+ Покормить", DayPaper.bg) // PRIMARY, not ACCENT
        check("Фото $initialCount", DayPaper.ink) // SECONDARY, including zero
        assertThat(feeds).isEqualTo(1)
        assertThat(galleries).isEqualTo(1)
        rule.runOnIdle { count.value = 123 }
        rule.onNodeWithText("Фото $initialCount").assertDoesNotExist()
        check("Фото 123", DayPaper.ink)
    }

    @Test fun zeroAlbumRemainsAnAccessibleActionAt320dp() = verify(1f, 0)
    @Test fun multiDigitLabelsKeepNativeActionsAndFullTypeAtFontScale2() = verify(2f, 31)
}
