package com.polinalinen.madre.ui.screens

import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.db.entities.FeedingEntity
import com.polinalinen.madre.data.db.entities.StorageLocation
import com.polinalinen.madre.model.RuShortDate
import com.polinalinen.madre.ui.kit.RazvorotTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w320dp-h640dp-mdpi", application = MadreApplication::class)
class DiaryRepeatUiTest {
    @get:Rule val rule = createComposeRule()
    private val older = FeedingEntity(id = 17, sourdoughConfigId = 1, timestampMillis = 1_750_000_000_000,
        retainedStarterGrams = 47, flourGrams = 113, waterGrams = 59, storageLocation = StorageLocation.FRIDGE)
    private val repeats = mutableListOf<FeedingEntity>()
    private lateinit var listState: LazyListState
    private fun row(value: FeedingEntity = older) = rule.onNodeWithTag("diary-entry-${value.id}")
    private fun disclosure(value: FeedingEntity = older) = rule.onNodeWithContentDescription(
        RuShortDate.accessible(value.timestampMillis), substring = true)
    private fun show(rows: List<FeedingEntity> = listOf(older), scale: Float = 1f) {
        rule.setContent { RazvorotTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                listState = rememberLazyListState()
                LazyColumn(Modifier.testTag("repeat-list"), state = listState) {
                    items(rows, key = { it.id }) { JournalRow(it, 2026, repeats::add) }
                }
            }
        } }
    }
    @Test fun rightGestureRepeatsSelectedOddMassesWhileLeftAndVerticalNeverRepeatOrDelete() {
        val rows = (0..8).map { older.copy(id = 17L + it, timestampMillis = older.timestampMillis - it * 86_400_000L) }
        show(rows)
        row().performTouchInput { swipeLeft() }
        assertThat(repeats).isEmpty(); row().assertExists()
        row().performTouchInput { swipeRight() }
        assertThat(repeats).containsExactly(older)
        // Only the touch gesture may move the list before this assertion.
        val beforeDrag = rule.runOnIdle { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
        rule.onNodeWithTag("repeat-list").performTouchInput { swipeUp() }
        rule.runOnIdle {
            val afterDrag = listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
            assertWithMessage("touch swipeUp must scroll LazyColumn: before=$beforeDrag after=$afterDrag")
                .that(afterDrag.first > beforeDrag.first ||
                    (afterDrag.first == beforeDrag.first && afterDrag.second > beforeDrag.second)).isTrue()
            assertThat(listState.layoutInfo.totalItemsCount).isEqualTo(rows.size)
            assertThat(repeats).containsExactly(older)
            println("D3 touch-only vertical scroll before=$beforeDrag after=$afterDrag rows=${rows.size}")
        }
        rule.onNodeWithTag("repeat-list").performScrollToNode(hasTestTag("diary-entry-25"))
        row(rows.last()).assertIsDisplayed()
        assertThat(repeats).containsExactly(older)
        rule.onNodeWithTag("repeat-list").performScrollToIndex(0)
        disclosure().performClick()
        rule.onNodeWithTag("diary-repeat-17").performScrollTo().assertIsDisplayed()
        disclosure().performScrollTo().performClick()
        rule.onNodeWithTag("diary-repeat-17").assertDoesNotExist()
    }
    @Test fun talkBackCustomActionInvokesTheExactRowWithoutRequiringASwipe() {
        show()
        val actions = disclosure().fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertThat(actions.map { it.label }).containsExactly("Повторить кормление")
        rule.runOnIdle { assertThat(actions.single().action()).isTrue() }
        assertThat(repeats).containsExactly(older)
    }
    @Test fun visibleActionFits320dpDoubleFontHas48dpTargetAndSupportsKeyboard() {
        lateinit var input: InputModeManager
        rule.setContent { RazvorotTheme {
            input = LocalInputModeManager.current
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                LazyColumn { item { JournalRow(older, 2026, repeats::add) } }
            }
        } }
        disclosure().performScrollTo().performClick()
        val button = rule.onNodeWithTag("diary-repeat-17").onChildAt(0).performScrollTo().assertIsEnabled().assertIsDisplayed()
        val bounds = button.fetchSemanticsNode().boundsInRoot
        assertThat(bounds.height).isAtLeast(48f)
        assertThat(bounds.width).isAtLeast(48f)
        val results = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("Повторить кормление", useUnmergedTree = true)
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertThat(it(results)).isTrue() }
        val result = results.single()
        assertWithMessage("${result.size} lines=${result.lineCount} width=${result.didOverflowWidth} height=${result.didOverflowHeight}")
            .that(result.hasVisualOverflow).isFalse()
        assertThat(result.layoutInput.density.fontScale).isEqualTo(2f)
        rule.runOnIdle { input.requestInputMode(InputMode.Keyboard) }
        button.performSemanticsAction(SemanticsActions.RequestFocus) { assertThat(it()).isTrue() }
        button.assertIsFocused().performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
        assertThat(repeats).containsExactly(older)
    }
    @Test fun unknownOrInvalidMassesExposeHonestDisabledReasonAndNoGestureOrTalkBackRepeat() {
        val value = mutableStateOf(older.copy(retainedStarterGrams = null))
        rule.setContent { RazvorotTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                LazyColumn { item { JournalRow(value.value, 2026, repeats::add) } }
            }
        } }
        disclosure().performScrollTo().performClick()
        fun disabled(reason: String) {
            val button = rule.onNodeWithTag("diary-repeat-17").onChildAt(0).performScrollTo().assertIsNotEnabled()
            button.performClick()
            rule.onNodeWithText(reason).performScrollTo().assertIsDisplayed()
            val results = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(reason).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertThat(it(results)).isTrue() }
            assertThat(results.single().hasVisualOverflow).isFalse()
            disclosure().performScrollTo().assert(SemanticsMatcher.keyNotDefined(SemanticsActions.CustomActions))
            disclosure().performTouchInput { swipeRight() }
            assertThat(repeats).isEmpty()
        }
        disabled("Повтор недоступен: в записи не указана масса оставленной закваски.")
        listOf(older.copy(retainedStarterGrams = 999), older.copy(flourGrams = 999), older.copy(waterGrams = 999)).forEach {
            rule.runOnIdle { value.value = it }
            disabled("Повтор недоступен: массы записи выходят за допустимые пределы.")
        }
        assertThat(value.value).isEqualTo(older.copy(waterGrams = 999))
    }
}
