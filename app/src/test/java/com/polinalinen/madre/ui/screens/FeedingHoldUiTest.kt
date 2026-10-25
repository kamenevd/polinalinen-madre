package com.polinalinen.madre.ui.screens

import androidx.compose.runtime.*
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.ui.kit.PaperStepButton
import com.polinalinen.madre.ui.kit.RazvorotTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi", application = MadreApplication::class)
class FeedingHoldUiTest {
    @get:Rule val rule = createComposeRule()
    private val enabled = mutableStateOf(true)
    private val visible = mutableStateOf(true)
    private val count = mutableStateOf(0)
    private lateinit var inputMode: InputModeManager
    private fun button() = rule.onNodeWithText("+")
    private fun fixture(repeat: Boolean = true): StateRestorationTester = StateRestorationTester(rule).also {
        it.setContent { RazvorotTheme {
            inputMode = LocalInputModeManager.current
            if (visible.value) {
                val captured = count.value
                PaperStepButton("+", "Прибавить", enabled.value, { count.value = captured + 1 },
                    accelerateOnHold = repeat)
            }
        } }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
    }
    private fun tick(ms: Long) {
        rule.runOnIdle { Snapshot.sendApplyNotifications() }
        rule.mainClock.advanceTimeBy(ms, ignoreFrameDuration = true)
        rule.waitForIdle()
    }
    private fun down(node: SemanticsNodeInteraction = button()) = node.performTouchInput { down(center) }
    private fun up(node: SemanticsNodeInteraction = button()) = node.performTouchInput { up() }
    private fun value(): Int { var result = 0; rule.runOnIdle { result = count.value }; return result }
    private fun step(label: String, plus: Boolean = true) = rule.onNode(SemanticsMatcher("step $label") {
        it.config.getOrNull(SemanticsActions.OnClick)?.label ==
            (if (plus) "Прибавить 5 граммов: " else "Убавить на 5 граммов: ") + label
    })
    private fun mass(label: String): Int = rule.onNode(SemanticsMatcher("mass $label") {
        it.config.getOrNull(SemanticsProperties.ContentDescription)?.any { text -> text.startsWith("$label: ") } == true
    }).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        .substringAfter(": ").substringBefore(" г").toInt()
    private fun form() {
        rule.setContent { RazvorotTheme { FeedingFormScreen(onSave = { _, _, _, _, _, _ -> }, onBack = {}) } }
    }
    private fun hold(node: SemanticsNodeInteraction, ms: Long) {
        node.performScrollTo().assertIsDisplayed()
        rule.mainClock.autoAdvance = false
        down(node); tick(ms); up(node); tick(32)
        rule.mainClock.autoAdvance = true
    }

    @Test fun `F3 real feeding plus gains at least forty grams in one second without release click`() {
        form()
        val plus = step("Оставленная закваска").performScrollTo()
        rule.mainClock.autoAdvance = false
        down(plus); tick(1000)
        val held = mass("Оставленная закваска")
        println("F3: starter after 1000ms=$held g (initial=50 g)")
        assertThat(held - 50).isAtLeast(40)
        assertThat(held).isAtMost(250)
        // Flush the last already-applied tick into semantics before comparing the stopped value.
        up(plus); tick(32)
        val released = mass("Оставленная закваска")
        tick(1000)
        assertThat(mass("Оставленная закваска")).isEqualTo(released)
    }
    @Test fun `real feeding taps are exactly five grams both directions for all masses`() {
        form()
        listOf("Оставленная закваска" to 50, "Добавленная мука" to 100, "Добавленная вода" to 50)
            .forEach { (label, initial) ->
                step(label).performScrollTo().performTouchInput { click() }
                assertThat(mass(label)).isEqualTo(initial + 5)
                step(label, false).performScrollTo().performTouchInput { click() }
                assertThat(mass(label)).isEqualTo(initial)
            }
    }
    @Test fun `real feeding long holds clamp every range and stay disabled at the edge`() {
        form()
        listOf(Triple("Оставленная закваска", 5, 250), Triple("Добавленная мука", 10, 500),
            Triple("Добавленная вода", 5, 300)).forEach { (label, min, max) ->
            hold(step(label), 6000)
            assertThat(mass(label)).isEqualTo(max)
            step(label).assertIsNotEnabled()
            hold(step(label, false), 6000)
            assertThat(mass(label)).isEqualTo(min)
            step(label, false).assertIsNotEnabled()
        }
    }
    @Test fun `repeat accelerates with fresh recomposed callback and stops on release`() {
        fixture()
        down(); tick(256)
        assertThat(value()).isEqualTo(0)
        tick(256)
        val slow = value()
        assertThat(slow).isAtLeast(2)
        tick(512)
        val held = value()
        assertThat(held - slow).isGreaterThan(slow)
        up(); tick(1000)
        assertThat(value()).isEqualTo(held)
        button().performTouchInput { click() }; tick(32)
        assertThat(value()).isEqualTo(held + 1)
    }
    @Test fun `cancel before threshold and outside drag never click or leave a timer`() {
        fixture()
        down(); tick(100)
        button().performTouchInput { cancel() }; tick(1000)
        assertThat(value()).isEqualTo(0)
        down(); tick(100)
        button().performTouchInput { moveTo(Offset(-200f, -200f)); up() }; tick(1000)
        assertThat(value()).isEqualTo(0)
        down(); tick(700)
        button().performTouchInput { cancel() }; tick(32)
        val cancelled = value()
        assertThat(cancelled).isGreaterThan(0)
        tick(1000)
        assertThat(value()).isEqualTo(cancelled)
    }
    @Test fun `disable while pressed cancels until a new gesture`() {
        fixture()
        down(); tick(700)
        rule.runOnIdle { enabled.value = false }; tick(32)
        val stopped = value()
        button().assertIsNotEnabled()
        tick(1000); up(); tick(32)
        assertThat(value()).isEqualTo(stopped)
        rule.runOnIdle { enabled.value = true }; tick(1000)
        assertThat(value()).isEqualTo(stopped)
        button().performTouchInput { click() }; tick(32)
        assertThat(value()).isEqualTo(stopped + 1)
    }
    @Test fun `disposal and restoration do not resume the old gesture`() {
        val restore = fixture()
        down(); tick(700)
        rule.runOnIdle { visible.value = false }; tick(32)
        val stopped = value()
        tick(1000)
        assertThat(value()).isEqualTo(stopped)
        rule.runOnIdle { visible.value = true }; tick(32)
        button().performTouchInput { cancel() }
        down(); tick(700)
        // RestorationTester must render its remove/reinsert frames, not conflate them.
        rule.mainClock.autoAdvance = true
        restore.emulateSavedInstanceStateRestore()
        rule.mainClock.autoAdvance = false
        tick(32)
        val restored = value()
        tick(1000)
        assertThat(value()).isEqualTo(restored)
        button().performTouchInput { cancel() }
        button().performTouchInput { click() }; tick(32)
        assertThat(value()).isEqualTo(restored + 1)
    }
    @Test fun `default nonfeeding hold remains one click on release`() {
        fixture(repeat = false)
        down(); tick(1000)
        assertThat(value()).isEqualTo(0)
        up(); tick(32)
        assertThat(value()).isEqualTo(1)
    }
    @Test fun `opt in retains button action label accessibility and keyboard single step`() {
        fixture()
        accessibleKeyStep()
    }
    @Test fun `default nonfeeding retains accessibility and keyboard single step`() {
        fixture(repeat = false)
        accessibleKeyStep()
    }
    private fun accessibleKeyStep() {
        button().assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        assertThat(button().fetchSemanticsNode().config[SemanticsActions.OnClick].label).isEqualTo("Прибавить")
        button().performClick(); tick(1000)
        assertThat(value()).isEqualTo(1)
        rule.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        button().performSemanticsAction(SemanticsActions.RequestFocus) { it() }
        tick(32)
        button().assertIsFocused()
        button().performKeyInput { keyDown(Key.Enter) }; tick(1000)
        assertThat(value()).isEqualTo(1)
        button().performKeyInput { keyUp(Key.Enter) }; tick(32)
        assertThat(value()).isEqualTo(2)
    }
}
