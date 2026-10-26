package com.polinalinen.madre.ui.screens

import androidx.compose.foundation.lazy.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.db.entities.*
import com.polinalinen.madre.sourdough.*
import com.polinalinen.madre.ui.kit.RazvorotTheme
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w320dp-h640dp-mdpi", application = MadreApplication::class)
class DiaryDeleteUiTest {
    @get:Rule val rule = createComposeRule()
    private val value = FeedingEntity(id = 17, sourdoughConfigId = 1, timestampMillis = 1_750_000_000_000,
        retainedStarterGrams = 47, flourGrams = 113, waterGrams = 59, storageLocation = StorageLocation.FRIDGE)
    @Test fun leftDeletesRightRepeatsTapDisclosesAndVerticalActuallyScrollsWithoutDeleting() {
        val deleted = mutableListOf<FeedingEntity>(); val repeated = mutableListOf<FeedingEntity>()
        lateinit var state: LazyListState
        rule.setContent { RazvorotTheme {
            state = rememberLazyListState()
            LazyColumn(Modifier.testTag("delete-list"), state = state) {
                items((0..8).map { value.copy(id = 17L + it) }) { JournalRow(it, 2026, repeated::add, deleted::add) }
            }
        } }
        rule.onNodeWithTag("diary-entry-17").performTouchInput { swipeRight() }
        assertEquals(listOf(value), repeated); assertTrue(deleted.isEmpty())
        rule.onNodeWithTag("diary-entry-17").onChildAt(0).performClick()
        rule.onNodeWithTag("diary-delete-17").assertExists(); assertTrue(deleted.isEmpty())
        val before = rule.runOnIdle { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
        rule.onNodeWithTag("delete-list").performTouchInput { swipeUp() }
        rule.runOnIdle {
            val after = state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset
            assertTrue("touch-only displacement $before -> $after", after.first > before.first || (after.first == before.first && after.second > before.second))
            assertEquals(9, state.layoutInfo.totalItemsCount)
            assertTrue(deleted.isEmpty()); assertEquals(listOf(value), repeated)
        }
        rule.onNodeWithTag("delete-list").performScrollToIndex(0)
        rule.onNodeWithTag("diary-entry-17").performTouchInput { swipeLeft() }
        assertEquals(listOf(value), deleted); assertEquals(listOf(value), repeated)
    }
    @Test fun talkBackDeleteAvailableForUnknownMassesAndPendingDisablesNewRequests() {
        val deleted = mutableListOf<FeedingEntity>(); val enabled = mutableStateOf(true)
        val legacy = value.copy(retainedStarterGrams = null)
        rule.setContent { RazvorotTheme { LazyColumn { item { JournalRow(legacy, 2026, onDelete = deleted::add, deleteEnabled = enabled.value) } } } }
        val disclosure = rule.onNodeWithTag("diary-entry-17").onChildAt(0)
        val actions = disclosure.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf("Удалить кормление"), actions.map { it.label })
        rule.runOnIdle { assertTrue(actions.single().action()) }
        assertEquals(listOf(legacy), deleted)
        rule.runOnIdle { enabled.value = false }
        disclosure.assert(SemanticsMatcher.keyNotDefined(SemanticsActions.CustomActions))
        disclosure.performClick()
        rule.onNodeWithTag("diary-delete-17").onChildAt(0).performScrollTo().assertIsNotEnabled()
        rule.onNodeWithTag("diary-entry-17").performTouchInput { swipeLeft() }
        assertEquals(listOf(legacy), deleted)
    }
    @Test fun visibleDeleteAndUndoFitDoubleFont320dpWith48dpKeyboardTargetsAndNoTextOverflow() {
        val state = mutableStateOf(DiaryDeletionState())
        var undos = 0; lateinit var input: InputModeManager
        rule.setContent { RazvorotTheme {
            input = LocalInputModeManager.current
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                StarterDiaryScreen(2, GrowthPhase.PEAK, profileForInterval(24), emptyList(), listOf(value), {}, {},
                    nowMillis = value.timestampMillis + 1_000, deletion = state.value,
                    onDelete = { state.value = DiaryDeletionState(PendingDiaryDeletion(1, DiaryBook(1, 1), it, 5_000)) },
                    onUndoDelete = { assertEquals(1L, it); undos++; state.value = DiaryDeletionState() })
            }
        } }
        rule.onNodeWithTag("diary-list").performScrollToNode(hasTestTag("diary-entry-17"))
        rule.onNodeWithTag("diary-entry-17").onChildAt(0).performClick()
        fun keyboard(tag: String) {
            val button = rule.onNodeWithTag(tag).onChildAt(0)
            if (tag != "diary-delete-undo") button.performScrollTo()
            button.assertIsDisplayed().assertIsEnabled()
            val bounds = button.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.height >= 48); assertTrue(bounds.width >= 48)
            rule.runOnIdle { input.requestInputMode(InputMode.Keyboard) }
            button.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
            button.assertIsFocused().performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
        }
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText("Удалить кормление", useUnmergedTree = true).performScrollTo()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        assertFalse(layouts.single().hasVisualOverflow)
        keyboard("diary-delete-17")
        rule.onNodeWithTag("diary-entry-17").assertDoesNotExist()
        listOf("Запись скрыта. Можно вернуть в течение 5 секунд.", "Вернуть").forEach { label ->
            val results = mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
            assertFalse("$label: ${results.single().size}", results.single().hasVisualOverflow)
        }
        keyboard("diary-delete-undo"); assertEquals(1, undos)
        rule.onNodeWithTag("diary-list").performScrollToNode(hasTestTag("diary-entry-17"))
        rule.onNodeWithTag("diary-entry-17").assertExists()
    }
}
