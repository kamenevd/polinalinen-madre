package com.polinalinen.madre.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.sourdough.GrowthPhase
import com.polinalinen.madre.ui.kit.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w320dp-h640dp-xhdpi", application = MadreApplication::class)
class DiaryHeroContrastUiTest {
    @get:Rule val rule = createComposeRule()
    private fun layout(node: SemanticsNodeInteraction): TextLayoutResult {
        val results = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertThat(it(results)).isTrue() }
        return results.single()
    }
    private fun contrast(foreground: Color, background: Color): Float {
        val first = foreground.compositeOver(background).luminance()
        val second = background.luminance()
        return (maxOf(first, second) + .05f) / (minOf(first, second) + .05f)
    }
    @Test fun `actual measured text meets AA on every composited theme and phase at narrow double scale`() {
        val theme = mutableStateOf(Atmosphere.DAY)
        val phase = mutableStateOf(GrowthPhase.EMPTY)
        rule.setContent {
            RazvorotTheme(force = theme.value) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                    androidx.compose.foundation.lazy.LazyColumn( androidx.compose.ui.Modifier.testTag("contrast-list")) {
                        item { DiaryHero(phase.value, emptyList(), 1_000, 445_501_000) }
                    }
                }
            }
        }
        val failures = mutableListOf<String>()
        listOf(Atmosphere.DAWN to DawnPaper, Atmosphere.DAY to DayPaper,
            Atmosphere.DUSK to DuskPaper, Atmosphere.NIGHT to NightPaper).forEach { (atmosphere, p) ->
            GrowthPhase.entries.forEach { next ->
                rule.runOnIdle { theme.value = atmosphere; phase.value = next }
                val phaseInk = if (next == GrowthPhase.PEAK) p.sage else p.acc
                val card = p.card.compositeOver(p.bg)
                val badge = phaseInk.copy(alpha = .12f).compositeOver(card)
                val phaseNode = rule.onNodeWithTag("diary-phase-${next.name}")
                    .onChildren().filter(hasText("", substring = true)).onFirst()
                listOf(Triple("phase", phaseNode, badge),
                    Triple("hydration", rule.onNodeWithTag("diary-hydration"), card),
                    Triple("note", rule.onNodeWithTag("diary-feeding-note"), card),
                    Triple("editorial label", rule.onNodeWithText("Мадре пишет · литературная реплика"), card),
                    Triple("voice", rule.onNodeWithTag("diary-voice"), card),
                    Triple("elapsed", rule.onNodeWithTag("diary-elapsed"), card)).forEach { (label, node, background) ->
                    val result = layout(node.performScrollTo())
                    val style = result.layoutInput.style
                    // WCAG large text: 18pt normal (24sp), or 14pt bold (18.667sp).
                    val large = style.fontSize.value >= 24f ||
                        (style.fontSize.value >= 18.667f && (style.fontWeight ?: FontWeight.Normal) >= FontWeight.Bold)
                    val minimum = if (large) 3f else 4.5f
                    val actual = contrast(style.color, background)
                    val message = "$atmosphere $next $label: $actual >= $minimum; size=${style.fontSize}, weight=${style.fontWeight}, fg=${style.color}, bg=$background"
                    println(message)
                    if (actual < minimum) failures += message
                    if (result.hasVisualOverflow) failures += "$message; overflow: ${result.size}, lines=${result.lineCount}, width=${result.didOverflowWidth}, height=${result.didOverflowHeight}"
                    assertThat(result.layoutInput.density.fontScale).isEqualTo(2f)
                    if (label == "phase" || label == "hydration") {
                        assertThat(style.fontFamily).isEqualTo(Manrope)
                        assertThat(style.color).isEqualTo(if (label == "phase") phaseInk else p.acc)
                    }
                }
            }
        }
        assertWithMessage(failures.joinToString("\n")).that(failures).isEmpty()
        val hydration = layout(rule.onNodeWithTag("diary-hydration").performScrollTo()).layoutInput.style
        assertThat(hydration.fontSize).isEqualTo(24.sp)
        assertThat(hydration.fontWeight).isEqualTo(FontWeight.Normal)
        assertThat(hydration.lineHeight).isEqualTo(32.sp)
    }
}
