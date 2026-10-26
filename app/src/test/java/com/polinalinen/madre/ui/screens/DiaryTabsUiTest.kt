package com.polinalinen.madre.ui.screens

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.db.entities.FeedingEntity
import com.polinalinen.madre.data.db.entities.StorageLocation
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
class DiaryTabsUiTest {
    @get:Rule val rule = createComposeRule()
    private fun tab(label: String) = rule.onNodeWithContentDescription("Раздел дневника, $label")
    private fun reveal(label: String) {
        rule.onNodeWithTag("diary-list").performScrollToNode(hasContentDescription("Раздел дневника, $label"))
        tab(label).performScrollTo().assertIsDisplayed()
    }
    private fun verify(scale: Float, history: List<FeedingEntity>) {
        var feeds = 0
        var galleries = 0
        val restoration = StateRestorationTester(rule)
        restoration.setContent {
            RazvorotTheme(force = Atmosphere.DAY) {
                CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, scale)) {
                    StarterDiaryScreen(1, GrowthPhase.EMPTY, profileForInterval(24),
                        listOf(DiaryEntry("Сегодня", "Рукописная запись", false)), history,
                        {}, { feeds++ }, { galleries++ }, nowMillis = 0)
                }
            }
        }
        fun assertTabs(journal: Boolean) {
            listOf("Журнал", "Советы").forEachIndexed { index, label ->
                reveal(label)
                val node = tab(label).assertIsEnabled()
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
                if ((index == 0) == journal) node.assertIsSelected() else node.assertIsNotSelected()
                node.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "${index + 1} из 2"))
                val bounds = node.fetchSemanticsNode().boundsInRoot
                assertThat(bounds.height).isAtLeast(48f)
                val layouts = mutableListOf<TextLayoutResult>()
                rule.onNodeWithText(paperControlText(label).text, useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertThat(it(layouts)).isTrue() }
                val layout = layouts.single()
                assertWithMessage("$label scale=$scale size=${layout.size} lines=${layout.lineCount} " +
                    "width=${layout.didOverflowWidth} height=${layout.didOverflowHeight}")
                    .that(layout.hasVisualOverflow).isFalse()
            }
        }
        assertTabs(true)
        rule.onNodeWithText("СОВЕТЫ ИЗ РУКОВОДСТВА").assertDoesNotExist()
        // Re-selecting the active native segment is idempotent.
        tab("Журнал").performClick(); assertTabs(true)
        tab("Советы").performClick(); assertTabs(false)
        rule.onNodeWithTag("diary-list").performScrollToNode(hasText("СОВЕТЫ ИЗ РУКОВОДСТВА"))
        rule.onNodeWithText("СОВЕТЫ ИЗ РУКОВОДСТВА").assertIsDisplayed()
        rule.onNodeWithText("В буклете Levito Madre указано", substring = true).performScrollTo().assertIsDisplayed()
        rule.onNodeWithContentDescription("Дата, Закваска, Мука, Вода, Гидратация, Хранение").assertDoesNotExist()
        restoration.emulateSavedInstanceStateRestore()
        assertTabs(false)
        tab("Советы").performClick(); assertTabs(false)
        listOf("+ Покормить", "Фото 0", "Рукописная запись").forEach { label ->
            rule.onNodeWithTag("diary-list").performScrollToNode(hasText(label))
            rule.onNodeWithText(label).performScrollTo().assertIsDisplayed().also {
                if (label != "Рукописная запись") it.performClick()
            }
        }
        assertThat(feeds).isEqualTo(1); assertThat(galleries).isEqualTo(1)
        reveal("Журнал"); tab("Журнал").performClick(); assertTabs(true)
        rule.onNodeWithTag("diary-list").performScrollToNode(hasContentDescription(
            "Дата, Закваска, Мука, Вода, Гидратация, Хранение"))
        rule.onNodeWithContentDescription("Дата, Закваска, Мука, Вода, Гидратация, Хранение").assertIsDisplayed()
        history.lastOrNull()?.let {
            // No ten-row ceiling: materialize the actual last row and its unchanged disclosure.
            rule.onNodeWithTag("diary-list").performScrollToNode(hasText("ПОДРОБНЕЕ"))
            rule.onNodeWithText("ПОДРОБНЕЕ").performScrollTo().performClick()
            rule.onNodeWithText("Заметка: Последняя из 24").performScrollTo().assertIsDisplayed()
        }
    }
    @Test fun emptyDiaryUsesNativeSegmentsAndRestoresAdvice() = verify(1f, emptyList())
    @Test fun largeTextKeepsBothTabsAndAllExistingRowsAccessible() = verify(2f, (1L..24L).map { id ->
        FeedingEntity(id = id, sourdoughConfigId = 1, timestampMillis = id * 86_400_000,
            flourGrams = 100, waterGrams = 50, storageLocation = StorageLocation.KITCHEN,
            notes = if (id == 24L) "Последняя из 24" else null)
    })
}
