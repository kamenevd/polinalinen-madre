package com.polinalinen.madre.ui.kit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.MadreApplication
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w320dp-h640dp-mdpi", application = MadreApplication::class)
class PaperButtonLabelWidthTest {
    @get:Rule val rule = createComposeRule()

    private data class Scenario(val variant: PaperButtonVariant, val enabled: Boolean, val icon: Boolean)
    private data class Measured(val button: Rect, val text: Rect, val layout: TextLayoutResult,
        val caption: TextLayoutResult, val role: Role, val clickLabel: String?, val disabled: Boolean)

    private fun verify(scale: Float) {
        val scenarios = PaperButtonVariant.entries.flatMap { variant ->
            listOf(false, true).flatMap { enabled -> listOf(false, true).map { Scenario(variant, enabled, it) } }
        }
        val scenario = mutableStateOf(scenarios.first())
        val clicks = IntArray(3)
        val label = "+ Покормить"
        rule.setContent {
            RazvorotTheme(force = Atmosphere.DAY) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                    key(scenario.value) {
                        Column(Modifier.width(276.dp).verticalScroll(rememberScrollState())) {
                            val s = scenario.value
                            val icon = if (s.icon) Icons.Outlined.LocalFireDepartment else null
                            PaperButton(label, { clicks[0]++ }, Modifier.testTag("default"),
                                enabled = s.enabled, caption = "Подпись", variant = s.variant, icon = icon)
                            PaperButton(label, { clicks[1]++ }, Modifier.testTag("false"),
                                enabled = s.enabled, caption = "Подпись", variant = s.variant, icon = icon,
                                fillLabelWidth = false)
                            PaperButton(label, { clicks[2]++ }, Modifier.testTag("true"),
                                enabled = s.enabled, caption = "Подпись", variant = s.variant, icon = icon,
                                fillLabelWidth = true)
                        }
                    }
                }
            }
        }
        fun textLayout(tag: String, text: String): TextLayoutResult {
            val results = mutableListOf<TextLayoutResult>()
            rule.onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertThat(it(results)).isTrue() }
            return results.single()
        }
        fun measure(tag: String): Measured {
            val button = rule.onNode(hasClickAction() and hasAnyAncestor(hasTestTag(tag)))
                .assertIsDisplayed().fetchSemanticsNode()
            val text = rule.onNode(hasText(label) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
                .fetchSemanticsNode()
            return Measured(button.boundsInRoot, text.boundsInRoot, textLayout(tag, label),
                textLayout(tag, "Подпись"), button.config[SemanticsProperties.Role],
                button.config[SemanticsActions.OnClick].label, button.config.contains(SemanticsProperties.Disabled))
        }
        scenarios.forEach { s ->
            rule.runOnIdle { clicks.fill(0); scenario.value = s }
            val omitted = measure("default")
            val explicitFalse = measure("false")
            val filled = measure("true")
            assertThat(explicitFalse.button.size).isEqualTo(omitted.button.size)
            assertThat(explicitFalse.text.size).isEqualTo(omitted.text.size)
            assertThat(explicitFalse.text.left - explicitFalse.button.left)
                .isEqualTo(omitted.text.left - omitted.button.left)
            assertThat(explicitFalse.layout.layoutInput.constraints).isEqualTo(omitted.layout.layoutInput.constraints)
            assertThat(explicitFalse.layout.hasVisualOverflow).isEqualTo(omitted.layout.hasVisualOverflow)
            listOf(explicitFalse, filled).forEach { actual ->
                assertThat(actual.button.size).isEqualTo(omitted.button.size)
                assertThat(actual.text.height).isEqualTo(omitted.text.height)
                assertThat(actual.layout.layoutInput.style).isEqualTo(omitted.layout.layoutInput.style)
                assertThat(actual.layout.layoutInput.text).isEqualTo(omitted.layout.layoutInput.text)
                assertThat(actual.layout.layoutInput.density.density).isEqualTo(omitted.layout.layoutInput.density.density)
                assertThat(actual.layout.layoutInput.density.fontScale).isEqualTo(omitted.layout.layoutInput.density.fontScale)
                assertThat(actual.layout.layoutInput.layoutDirection).isEqualTo(omitted.layout.layoutInput.layoutDirection)
                assertThat(actual.layout.layoutInput.softWrap).isEqualTo(omitted.layout.layoutInput.softWrap)
                assertThat(actual.layout.layoutInput.maxLines).isEqualTo(omitted.layout.layoutInput.maxLines)
                assertThat(actual.layout.layoutInput.overflow).isEqualTo(omitted.layout.layoutInput.overflow)
                assertThat(actual.layout.lineCount).isEqualTo(omitted.layout.lineCount)
                assertThat(actual.caption.layoutInput.style).isEqualTo(omitted.caption.layoutInput.style)
                assertThat(actual.caption.layoutInput.text).isEqualTo(omitted.caption.layoutInput.text)
                assertThat(actual.caption.layoutInput.constraints).isEqualTo(omitted.caption.layoutInput.constraints)
                assertThat(actual.caption.size).isEqualTo(omitted.caption.size)
                assertThat(actual.role).isEqualTo(omitted.role)
                assertThat(actual.clickLabel).isEqualTo(omitted.clickLabel)
                assertThat(actual.disabled).isEqualTo(omitted.disabled)
                val actualCenter = actual.text.left - actual.button.left +
                    (actual.layout.getLineLeft(0) + actual.layout.getLineRight(0)) / 2
                if (actual === explicitFalse) {
                    val originalCenter = omitted.text.left - omitted.button.left +
                        (omitted.layout.getLineLeft(0) + omitted.layout.getLineRight(0)) / 2
                    assertThat(actualCenter).isEqualTo(originalCenter)
                } else {
                    // Fill fixes the old intrinsic-node/paragraph-width mismatch, not TextAlign.Center.
                    val centeredLabel = omitted.button.width / 2 + if (s.icon) 13f else 0f
                    assertThat(actualCenter).isWithin(1f).of(centeredLabel)
                }
            }
            assertThat(omitted.role).isEqualTo(Role.Button)
            assertThat(omitted.clickLabel).isEqualTo(label)
            assertThat(omitted.disabled).isEqualTo(!s.enabled)
            assertThat(filled.layout.size.width)
                .isEqualTo(filled.layout.layoutInput.constraints.maxWidth)
            assertThat(filled.layout.layoutInput.constraints.maxWidth)
                .isEqualTo(omitted.layout.layoutInput.constraints.maxWidth)
            assertThat(filled.layout.size.width).isGreaterThan(omitted.layout.size.width)
            assertThat(filled.layout.hasVisualOverflow).isFalse()
            listOf("default", "false", "true").forEach { tag ->
                val button = rule.onNode(hasClickAction() and hasAnyAncestor(hasTestTag(tag)))
                if (s.enabled) button.assertIsEnabled() else button.assertIsNotEnabled()
                button.performTouchInput { click() }
            }
            rule.runOnIdle { assertThat(clicks.toList()).containsExactlyElementsIn(List(3) { if (s.enabled) 1 else 0 }).inOrder() }
        }
    }

    @Test fun omittedAndExplicitFalseKeepLayoutStyleSemanticsAndClicks() = verify(1f)
    @Test fun optInOnlyExpandsLabelConstraintAtLargeFontAcrossAllVariants() = verify(2f)
}
