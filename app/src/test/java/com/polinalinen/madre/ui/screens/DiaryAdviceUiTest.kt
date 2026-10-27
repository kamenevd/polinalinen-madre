package com.polinalinen.madre.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
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
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w320dp-h640dp-mdpi", application = MadreApplication::class)
class DiaryAdviceUiTest {
    @get:Rule val rule = createComposeRule()
    // Approved original prose, split only after sentences two and three.
    private val paragraphs = listOf(
        "В буклете Levito Madre указано соотношение базового кормления 2:1:2 (закваска:вода:мука) и стартовая гидратация 50%. " +
            "Мадре и закваску кормят белой пшеничной мукой высшего сорта с содержанием белка >10 г на 100 г.",
        "Хранение в холодильнике при 4–6°C обычно даёт интервал кормления 3–5 дней.",
        "Пик чаще всего бывает около 3–5 часов, но фактически зависит от температуры, муки, печи и вашего ритма. " +
            "Стартовые значения формы 50/100/50 — это редактируемые удобные массы, а не формула.",
    )
    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertThat(it(results)).isTrue() }
        return results.single()
    }
    private fun contrast(foreground: Color, background: Color): Float {
        val a = foreground.compositeOver(background).luminance()
        val b = background.luminance()
        return (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
    }
    private fun verify(scale: Float) {
        val theme = mutableStateOf(Atmosphere.DAY)
        rule.setContent {
            RazvorotTheme(force = theme.value) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                    StarterDiaryScreen(1, GrowthPhase.EMPTY, profileForInterval(24), emptyList(), emptyList(),
                        {}, {}, nowMillis = 0)
                }
            }
        }
        val list = rule.onNodeWithTag("diary-list")
        fun tab(label: String) {
            list.performScrollToNode(hasContentDescription("Раздел дневника, $label"))
            rule.onNodeWithContentDescription("Раздел дневника, $label").performScrollTo()
                .assertIsDisplayed().performClick().assertIsSelected()
        }
        fun position() = list.fetchSemanticsNode().config[SemanticsProperties.VerticalScrollAxisRange].value()
        listOf(Atmosphere.DAWN to DawnPaper, Atmosphere.DAY to DayPaper,
            Atmosphere.DUSK to DuskPaper, Atmosphere.NIGHT to NightPaper).forEach { (atmosphere, paper) ->
            rule.runOnIdle { theme.value = atmosphere }
            tab("Журнал")
            rule.onNodeWithTag("diary-advice-card").assertDoesNotExist()
            rule.onNodeWithText("СОВЕТЫ ИЗ РУКОВОДСТВА").assertDoesNotExist()
            tab("Советы")
            list.performScrollToNode(hasText("СОВЕТЫ ИЗ РУКОВОДСТВА"))
            val heading = rule.onNodeWithText("СОВЕТЫ ИЗ РУКОВОДСТВА").performScrollTo().assertIsDisplayed()
            val headingLayout = layout(heading)
            assertWithMessage("$atmosphere heading size=${headingLayout.size} lines=${headingLayout.lineCount} " +
                "width=${headingLayout.didOverflowWidth} height=${headingLayout.didOverflowHeight}")
                .that(headingLayout.hasVisualOverflow).isFalse()
            assertThat(contrast(headingLayout.layoutInput.style.color, paper.bg)).isAtLeast(4.5f)
            // Start away from the bottom: at scale one all paragraphs can already fit below the heading.
            // Exercise the actual touch-scrolled LazyColumn before any paragraph scroll action.
            list.performScrollToIndex(0)
            val before = position()
            list.performTouchInput { swipeUp() }
            rule.waitForIdle()
            assertWithMessage("$atmosphere scale=$scale real vertical gesture").that(position()).isGreaterThan(before)
            val rendered = paragraphs.indices.map { index ->
                val node = rule.onNodeWithTag("diary-advice-paragraph-$index")
                    .assert(hasAnyAncestor(hasTestTag("diary-advice-card")))
                    .performScrollTo().assertIsDisplayed().assertTextEquals(paragraphs[index])
                val result = layout(node)
                val style = result.layoutInput.style
                assertWithMessage("$atmosphere paragraph=$index scale=$scale size=${result.size} lines=${result.lineCount} " +
                    "width=${result.didOverflowWidth} height=${result.didOverflowHeight}")
                    .that(result.hasVisualOverflow).isFalse()
                assertThat(result.layoutInput.density.fontScale).isEqualTo(scale)
                assertThat(style.fontSize).isEqualTo(13.sp)
                assertThat(style.lineHeight).isEqualTo(19.sp)
                assertThat(style.fontFamily).isEqualTo(PaperType.body.fontFamily)
                assertThat(style.color).isEqualTo(paper.ink2)
                val aa = contrast(style.color, paper.card.compositeOver(paper.bg))
                println("$atmosphere paragraph=$index scale=$scale AA=$aa lines=${result.lineCount} overflow=${result.hasVisualOverflow}")
                assertThat(aa).isAtLeast(4.5f)
                result.layoutInput.text.text
            }
            // Count actual Text nodes and retain their traversal order, not merely fixture constants.
            val texts = rule.onAllNodes(hasAnyAncestor(hasTestTag("diary-advice-card")) and
                SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()
                .map { node -> node.config[SemanticsProperties.Text].single().text }
            assertThat(texts).containsExactlyElementsIn(rendered).inOrder()
            val joined = rendered.joinToString(" ")
            val hash = MessageDigest.getInstance("SHA-256").digest(joined.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
            assertThat(hash).isEqualTo("406d87d0fdb045fc3c262a9cc324b23ca232a655244bf54f72f367d5a0102fe5")
            println("$atmosphere scale=$scale original-prose-sha256=$hash paragraphs=${texts.size}")
            tab("Журнал")
            rule.onNodeWithTag("diary-advice-card").assertDoesNotExist()
        }
    }
    @Test fun originalProseInThreeCardParagraphsIsReadableInEveryAtmosphere() = verify(1f)
    @Test fun doubleScaleKeepsEveryOriginalParagraphReachableAndUnclippedInEveryAtmosphere() = verify(2f)
}
