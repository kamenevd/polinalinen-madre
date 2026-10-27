package com.polinalinen.madre.ui.screens

import androidx.compose.runtime.*
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.*
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.db.entities.*
import com.polinalinen.madre.sourdough.*
import com.polinalinen.madre.ui.kit.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w320dp-h640dp-mdpi", application = MadreApplication::class)
class DiaryEmptyUiTest {
    @get:Rule val rule = createComposeRule()
    private val name = "Бабушкина закваска Мария" // Supported maximum length; no production fallback.
    private val copy get() = "Дневник начнётся с первого кормления. $name ждёт."
    @Test fun longActualNameAtDoubleFontHasReadablePaperTextAndKeyboardFeedInEveryAtmosphere() {
        val palette = mutableStateOf(DayPaper)
        lateinit var input: InputModeManager
        var calls = 0
        rule.setContent { RazvorotTheme {
            input = LocalInputModeManager.current
            CompositionLocalProvider(LocalPaper provides palette.value,
                LocalDensity provides Density(LocalDensity.current.density, 2f)) {
                key(palette.value) {
                    StarterDiaryScreen(1, GrowthPhase.HUNGRY, profileForInterval(24), emptyList(), emptyList(), {},
                        { calls++ }, starterName = name, nowMillis = 1_750_000_000_000)
                }
            }
        } }
        listOf(DawnPaper, DayPaper, DuskPaper, NightPaper).forEach { paper ->
            rule.runOnIdle { palette.value = paper }
            rule.onNodeWithTag("diary-list").performScrollToNode(hasTestTag("diary-empty"))
            rule.onNodeWithText(copy).performScrollTo().assertIsDisplayed()
            listOf(copy, "Покормить").forEach { label ->
                val results = mutableListOf<TextLayoutResult>()
                rule.onNodeWithText(label, useUnmergedTree = true).performScrollTo()
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(results)) }
                val result = results.single()
                assertFalse("$label size=${result.size} lines=${result.lineCount} width=${result.didOverflowWidth} height=${result.didOverflowHeight}",
                    result.hasVisualOverflow)
                val expected = if (label == "Покормить") paper.bg else paper.ink2
                assertEquals(expected, result.layoutInput.style.color)
                val bg = if (label == "Покормить") paper.ink else paper.bg
                val a = expected.luminance(); val b = bg.luminance()
                val contrast = (maxOf(a, b) + .05f) / (minOf(a, b) + .05f)
                println("DiaryEmpty contrast label=$label palette=$paper ratio=$contrast")
                assertTrue("actual text contrast $contrast", contrast >= 4.5f)
            }
            val button = rule.onNodeWithTag("diary-empty-feed").onChildAt(0)
            button.performScrollTo().assertIsDisplayed().assertIsEnabled()
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            assertEquals("Покормить", button.fetchSemanticsNode().config[SemanticsActions.OnClick].label)
            val bounds = button.fetchSemanticsNode().boundsInRoot
            assertTrue(bounds.height >= 48); assertTrue(bounds.width >= 48)
            rule.runOnIdle { input.requestInputMode(InputMode.Keyboard) }
            button.performSemanticsAction(SemanticsActions.RequestFocus) { assertTrue(it()) }
            button.assertIsFocused().performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
        }
        assertEquals(4, calls)
    }

    @Test fun adviceAndNonemptyAndHiddenOnlyPendingRowNeverBecomeFirstRecordEmpty() {
        val row = FeedingEntity(id = 17, sourdoughConfigId = 1, timestampMillis = 1_750_000_000_000,
            flourGrams = 113, waterGrams = 59, storageLocation = StorageLocation.FRIDGE)
        val history = mutableStateOf(listOf(row)); val tab = mutableStateOf(0)
        val deletion = mutableStateOf(DiaryDeletionState())
        rule.setContent { RazvorotTheme {
            StarterDiaryScreen(1, GrowthPhase.PEAK, profileForInterval(24), emptyList(), history.value, {}, {},
                starterName = name, selectedTabIndex = tab.value, onTabSelected = { tab.value = it },
                deletion = deletion.value, nowMillis = row.timestampMillis)
        } }
        rule.onNodeWithTag("diary-list").performScrollToNode(hasTestTag("diary-entry-17"))
        rule.onNodeWithTag("diary-empty").assertDoesNotExist()
        rule.runOnIdle { deletion.value = DiaryDeletionState(PendingDiaryDeletion(1, DiaryBook(1, 1), row, 5_000)) }
        rule.onNodeWithTag("diary-list").performScrollToIndex(2)
        assertEquals(-1, rule.onNodeWithTag("diary-list").fetchSemanticsNode().config[SemanticsProperties.IndexForKey]("diary-empty"))
        rule.onNodeWithTag("diary-entry-17").assertDoesNotExist()
        rule.onNodeWithTag("diary-empty").assertDoesNotExist()
        rule.onNodeWithTag("diary-delete-undo").onChildAt(0).assertIsDisplayed().assertIsEnabled()
        rule.runOnIdle { history.value = emptyList(); deletion.value = DiaryDeletionState(); tab.value = 1 }
        rule.onNodeWithTag("diary-list").performScrollToNode(hasText("СОВЕТЫ ИЗ РУКОВОДСТВА"))
        rule.onNodeWithTag("diary-empty").assertDoesNotExist()
        rule.onNodeWithText("В буклете Levito Madre", substring = true).assertExists()
        rule.runOnIdle { tab.value = 0 }
        rule.onNodeWithTag("diary-list").performScrollToNode(hasTestTag("diary-empty"))
        rule.onNodeWithText(copy).assertExists()
    }
}
