package com.polinalinen.madre.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.sourdough.HydrationMath
import com.polinalinen.madre.ui.kit.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi", application = MadreApplication::class)
class FeedingSummaryUiTest {
    @get:Rule val rule = createComposeRule()
    private fun layers() = rule.onNodeWithTag("feeding-mass-jar").fetchSemanticsNode().config[FeedingLayerFractions]
    private fun assertLayers(s: Int, f: Int, w: Int) {
        val expected = FeedingSummaryMasses(s, f, w).fractions
        layers().forEachIndexed { i, value -> assertThat(value).isWithin(.0001f).of(expected[i]) }
    }
    private fun exact(label: String, from: Int, to: Int) {
        rule.onNodeWithContentDescription("$label: $from г").performScrollTo().performClick()
        rule.onNodeWithContentDescription("$label, точный вес в граммах").apply {
            performTextClearance(); performTextInput(to.toString())
        }
        rule.onNodeWithText("записать вес").performScrollTo().performClick()
    }
    private fun summary(total: Int, ratio: String, hydration: Int) {
        rule.onNodeWithText("Станет в банке $total г").performScrollTo().assertIsDisplayed()
        rule.onNodeWithText(ratio).assertExists()
        rule.onNodeWithContentDescription("Гидратация после кормления: $hydration процентов, посчитана книгой").assertExists()
    }
    @Test fun `hydration layout inherits Manrope and retains size weight line height and accent`() {
        var accent = Color.Unspecified
        rule.setContent { RazvorotTheme {
            accent = LocalPaper.current.acc
            FeedingSummary(FeedingSummaryMasses(50, 100, 50), 55, "Считаем от прошлой гидратации 72%")
        } }
        val layouts = mutableListOf<TextLayoutResult>()
        val action = rule.onNodeWithText("55%").fetchSemanticsNode()
            .config[SemanticsActions.GetTextLayoutResult].action
        rule.runOnIdle {
            assertThat(action?.invoke(layouts)).isTrue()
            assertThat(layouts).hasSize(1)
            val style = layouts.single().layoutInput.style
            assertThat(style.fontFamily).isEqualTo(Manrope)
            assertThat(style.fontSize).isEqualTo(46.sp)
            assertThat(style.lineHeight).isEqualTo(52.sp)
            assertThat(style.fontWeight).isEqualTo(FontWeight.ExtraLight)
            assertThat(style.color).isEqualTo(accent)
        }
    }
    @Test fun `F1 uses real masses prior 72 and unchanged hydration math`() {
        rule.setContent { RazvorotTheme { FeedingFormScreen(onSave = { _, _, _, _, _, _ -> }, onBack = {}, priorHydrationPercent = 72) } }
        summary(200, "1 : 2 : 1", 55)
        rule.onNodeWithText("Считаем от прошлой гидратации 72%").assertExists()
        assertLayers(50, 100, 50)
        rule.onNodeWithTag("feeding-mass-jar").assertContentDescriptionEquals(
            "Слои по массе: закваска 50 г, мука 100 г, вода 50 г. Соотношение масс, не измерение объёма или роста")
    }
    @Test fun `no history exact edits preset and restoration update summary without changing save`() {
        var saved: List<Int>? = null
        val restoration = StateRestorationTester(rule)
        restoration.setContent { RazvorotTheme { FeedingFormScreen(
            onSave = { s, f, w, _, _, _ -> saved = listOf(s, f, w) }, onBack = {}) } }
        summary(200, "1 : 2 : 1", 50)
        rule.onNodeWithText("Считаем от стартовой гидратации 50% — посчитанных кормлений в дневнике ещё нет").assertExists()
        exact("Оставленная закваска", 50, 61)
        exact("Добавленная мука", 100, 113)
        exact("Добавленная вода", 50, 59)
        val hydration = HydrationMath.finalHydrationPercent(61, 50, 113, 59)
        summary(233, "61 : 113 : 59", hydration)
        assertLayers(61, 113, 59)
        restoration.emulateSavedInstanceStateRestore()
        summary(233, "61 : 113 : 59", hydration)
        assertLayers(61, 113, 59)
        rule.onNodeWithText("Вписать в дневник").performScrollTo().performClick()
        rule.runOnIdle { assertThat(saved).containsExactly(61, 113, 59).inOrder() }
        rule.onNodeWithTag("paper-list").performScrollToIndex(0)
        rule.onNodeWithContentDescription("1 : 2 : 2").performScrollTo().performClick()
        summary(301, "61 : 120 : 120", HydrationMath.finalHydrationPercent(61, 50, 120, 120))
        assertLayers(61, 120, 120)
        // Save once per form instance: PaperButton deliberately rejects rapid duplicate taps.
        restoration.emulateSavedInstanceStateRestore()
        summary(301, "61 : 120 : 120", HydrationMath.finalHydrationPercent(61, 50, 120, 120))
        rule.onNodeWithText("Вписать в дневник").performScrollTo().performClick()
        rule.runOnIdle { assertThat(saved).containsExactly(61, 120, 120).inOrder() }
    }
    @Test fun `unequal masses reduce by common divisor without rounding layers`() {
        val masses = FeedingSummaryMasses(60, 120, 30)
        assertThat(masses.total).isEqualTo(210)
        assertThat(masses.ratio).isEqualTo("2 : 4 : 1")
        assertThat(masses.fractions).containsExactly(60f / 210, 120f / 210, 30f / 210).inOrder()
    }
    private fun motion(calm: Boolean = false, readingReduced: Boolean = false) {
        val context = ApplicationProvider.getApplicationContext<MadreApplication>()
        ReadingPreferences(context).setReduced(readingReduced)
        val masses = mutableStateOf(FeedingSummaryMasses(50, 100, 50))
        val calmState = mutableStateOf(calm)
        rule.setContent { RazvorotTheme {
            CompositionLocalProvider(LocalCalmMode provides calmState.value) {
                FeedingSummary(masses.value, 50, "Считаем от прошлой гидратации 50%")
            }
        } }
        assertLayers(50, 100, 50) // No zero/placeholder entry animation.
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { masses.value = FeedingSummaryMasses(60, 120, 30); Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        if (calm || readingReduced) {
            assertLayers(60, 120, 30)
        } else {
            rule.mainClock.advanceTimeBy(240, ignoreFrameDuration = true)
            val middle = layers()
            assertThat(middle[2]).isGreaterThan(30f / 210)
            assertThat(middle[2]).isLessThan(50f / 200)
            assertThat(middle.sum()).isWithin(.0001f).of(1f)
            // A new target interrupts the running tween, never a stale remembered mass.
            rule.runOnIdle { masses.value = FeedingSummaryMasses(47, 113, 59); Snapshot.sendApplyNotifications() }
            rule.mainClock.advanceTimeByFrame()
            rule.mainClock.advanceTimeByFrame()
            rule.mainClock.advanceTimeBy(500, ignoreFrameDuration = true)
            rule.mainClock.advanceTimeByFrame()
            assertLayers(47, 113, 59)
            // Switching calm on during a later edit bypasses the tween immediately.
            rule.runOnIdle { masses.value = FeedingSummaryMasses(60, 120, 30); calmState.value = true; Snapshot.sendApplyNotifications() }
            rule.mainClock.advanceTimeByFrame()
            assertLayers(60, 120, 30)
        }
        rule.mainClock.autoAdvance = true
        ReadingPreferences(context).setReduced(false)
    }
    @Test fun `three real layers animate for 500ms and retarget edits`() = motion()
    @Test fun `calm mode snaps all layers without animation`() = motion(calm = true)
    @Test fun `reading reduced motion independently snaps all layers`() = motion(readingReduced = true)
}
