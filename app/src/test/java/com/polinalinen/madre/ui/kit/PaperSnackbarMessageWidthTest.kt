package com.polinalinen.madre.ui.kit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.MadreApplication
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w320dp-h640dp-mdpi", application = MadreApplication::class)
class PaperSnackbarMessageWidthTest {
    @get:Rule val rule = createComposeRule()

    private fun verify(withAction: Boolean) {
        val theme = mutableStateOf(Atmosphere.DAY)
        val scale = mutableStateOf(1f)
        val hosts = List(3) { PaperSnackbarState() }
        val message = "Файл не найден"
        rule.setContent {
            RazvorotTheme(force = theme.value) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale.value)) {
                    // All three hosts receive identical vertical constraints, including at scale 2.
                    Column(Modifier.width(296.dp).verticalScroll(rememberScrollState())) {
                        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().testTag("omitted")) {
                            PaperSnackbar(hosts[0], Modifier.fillMaxWidth())
                        }
                        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().testTag("false")) {
                            PaperSnackbar(hosts[1], Modifier.fillMaxWidth(), fillMessageWidth = false)
                        }
                        androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().testTag("true")) {
                            PaperSnackbar(hosts[2], Modifier.fillMaxWidth(), fillMessageWidth = true)
                        }
                    }
                    hosts.forEach { host ->
                        LaunchedEffect(host) {
                            host.showSnackbar(message, if (withAction) "Ещё" else null, indefinite = true)
                        }
                    }
                }
            }
        }
        fun text(tag: String) = rule.onNode(hasText(message) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
        fun layout(tag: String): TextLayoutResult {
            val results = mutableListOf<TextLayoutResult>()
            text(tag).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
            return results.single()
        }
        for (atmosphere in Atmosphere.entries.filter { it != Atmosphere.AUTO }) for (font in listOf(1f, 2f)) {
            rule.runOnIdle { theme.value = atmosphere; scale.value = font }
            val omitted = layout("omitted")
            val explicitFalse = layout("false")
            val filled = layout("true")
            val diagnostic = "$atmosphere scale=$font action=$withAction size=${filled.size} constraints=${filled.layoutInput.constraints} overflow=${filled.hasVisualOverflow}"
            println(diagnostic)
            assertEquals(omitted.size, explicitFalse.size)
            assertEquals(omitted.layoutInput.constraints, explicitFalse.layoutInput.constraints)
            assertEquals(omitted.hasVisualOverflow, explicitFalse.hasVisualOverflow)
            assertEquals(omitted.didOverflowWidth, explicitFalse.didOverflowWidth)
            assertEquals(omitted.didOverflowHeight, explicitFalse.didOverflowHeight)
            assertEquals(omitted.lineCount, explicitFalse.lineCount)
            for (line in 0 until omitted.lineCount) {
                assertEquals(omitted.getLineLeft(line), explicitFalse.getLineLeft(line), 0f)
                assertEquals(omitted.getLineRight(line), explicitFalse.getLineRight(line), 0f)
            }
            val omittedText = text("omitted").fetchSemanticsNode().boundsInRoot
            val falseText = text("false").fetchSemanticsNode().boundsInRoot
            val omittedHost = rule.onNodeWithTag("omitted").fetchSemanticsNode().boundsInRoot
            val falseHost = rule.onNodeWithTag("false").fetchSemanticsNode().boundsInRoot
            assertEquals(omittedText.left - omittedHost.left, falseText.left - falseHost.left, 0f)
            listOf(explicitFalse, filled).forEach { actual ->
                assertEquals(omitted.layoutInput.style, actual.layoutInput.style)
                assertEquals(omitted.layoutInput.text, actual.layoutInput.text)
                assertEquals(omitted.layoutInput.density.density, actual.layoutInput.density.density, 0f)
                assertEquals(omitted.layoutInput.density.fontScale, actual.layoutInput.density.fontScale, 0f)
                assertEquals(omitted.layoutInput.layoutDirection, actual.layoutInput.layoutDirection)
                assertEquals(omitted.layoutInput.maxLines, actual.layoutInput.maxLines)
                assertEquals(omitted.layoutInput.softWrap, actual.layoutInput.softWrap)
                assertEquals(omitted.layoutInput.overflow, actual.layoutInput.overflow)
            }
            assertEquals(PaperType.body.fontFamily, filled.layoutInput.style.fontFamily)
            assertEquals(PaperType.body.fontSize, filled.layoutInput.style.fontSize)
            assertEquals(omitted.layoutInput.constraints.maxWidth, filled.size.width)
            // A wrapped message beside an action can already consume the entire available width.
            if (omitted.size.width < omitted.layoutInput.constraints.maxWidth) {
                assertTrue(diagnostic, filled.size.width > omitted.size.width)
            } else {
                assertEquals(omitted.size.width, filled.size.width)
            }
            assertFalse(diagnostic, filled.hasVisualOverflow)
            for (tag in listOf("omitted", "false", "true")) {
                val action = rule.onNode(hasText("ЕЩЁ") and hasAnyAncestor(hasTestTag(tag)))
                if (withAction) action.assertHasClickAction() else action.assertDoesNotExist()
            }
        }
    }

    @Test fun omittedAndFalseKeepLegacyLayoutWhileOptInFillsOnlyMessage() = verify(false)
    @Test fun existingActionPresentationIsUnchangedAcrossThemesAndScales() = verify(true)
}
