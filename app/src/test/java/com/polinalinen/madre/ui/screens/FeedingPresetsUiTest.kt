package com.polinalinen.madre.ui.screens

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.db.entities.FeedingEntity
import com.polinalinen.madre.data.db.entities.StorageLocation
import com.polinalinen.madre.ui.kit.RazvorotTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi", application = MadreApplication::class)
class FeedingPresetsUiTest {
    @get:Rule val rule = createComposeRule()
    private val past = FeedingEntity(sourdoughConfigId = 1, retainedStarterGrams = 47,
        flourGrams = 113, waterGrams = 59, storageLocation = StorageLocation.FRIDGE,
        notes = "прошлая заметка", photoPath = "feeding_photos/old.jpg")
    private val history = mutableStateOf<FeedingEntity?>(null)
    private var saved: List<Any?>? = null
    private fun show(last: FeedingEntity? = null): StateRestorationTester {
        history.value = last
        return StateRestorationTester(rule).also { restoration -> restoration.setContent {
            RazvorotTheme { FeedingFormScreen(
                onSave = { s, f, w, l, n, p -> saved = listOf(s, f, w, l, n, p) },
                onBack = {}, lastFeeding = history.value,
            ) }
        } }
    }
    private fun chip(label: String) = rule.onNodeWithContentDescription(label)
    private fun choose(label: String) {
        // The horizontal row's ScrollTo action does not scroll its outer LazyColumn.
        rule.onNodeWithTag("paper-list").performScrollToIndex(0)
        chip(label).performScrollTo().assertIsDisplayed().performClick().assertIsSelected()
    }
    private fun masses(s: Int, f: Int, w: Int) {
        listOf("Оставленная закваска" to s, "Добавленная мука" to f, "Добавленная вода" to w)
            .forEach { (label, value) -> rule.onNodeWithContentDescription("$label: $value г").assertExists() }
    }
    private fun save(s: Int, f: Int, w: Int, location: StorageLocation = StorageLocation.KITCHEN) {
        rule.onNodeWithText("Вписать в дневник").performScrollTo().performClick()
        rule.runOnIdle { assertThat(saved).isEqualTo(listOf(s, f, w, location, null, null)) }
    }
    private fun exactStarter(value: Int) {
        rule.onNodeWithContentDescription("Оставленная закваска: 50 г").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Оставленная закваска, точный вес в граммах")
            .apply { performTextClearance(); performTextInput(value.toString()) }
        rule.onNodeWithText("записать вес").performClick()
    }

    @Test fun `no history leaves defaults and disables last`() {
        show()
        chip("Как в прошлый раз").assertIsNotEnabled().assertIsNotSelected()
        masses(50, 100, 50)
        save(50, 100, 50)
    }
    @Test fun `real last defaults exactly without copying note photo or location and can be reapplied`() {
        show(past)
        chip("Как в прошлый раз").assertIsSelected()
        masses(47, 113, 59)
        choose("1 : 2 : 2")
        masses(47, 95, 95)
        choose("Как в прошлый раз")
        masses(47, 113, 59)
        save(47, 113, 59)
    }
    @Test fun `late history fills only a pristine form once`() {
        show()
        rule.runOnIdle { history.value = past }
        chip("Как в прошлый раз").assertIsSelected()
        masses(47, 113, 59)
        rule.runOnIdle { history.value = past.copy(flourGrams = 120) }
        chip("Как в прошлый раз").assertIsNotSelected()
        save(47, 113, 59)
    }
    @Test fun `late history cannot overwrite manual exact grams or restore a preset selection`() {
        val restoration = show()
        choose("1 : 1 : 1")
        exactStarter(61)
        chip("1 : 1 : 1").assertIsNotSelected()
        restoration.emulateSavedInstanceStateRestore()
        rule.runOnIdle { history.value = past }
        masses(61, 50, 50)
        chip("Как в прошлый раз").assertIsNotSelected()
        save(61, 50, 50)
    }
    @Test fun `late history cannot overwrite restored untouched defaults`() {
        val restoration = show()
        restoration.emulateSavedInstanceStateRestore()
        rule.runOnIdle { history.value = past }
        masses(50, 100, 50)
        save(50, 100, 50)
    }
    @Test fun `opening exact input fences late history before confirmation`() {
        show()
        rule.onNodeWithContentDescription("Оставленная закваска: 50 г").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Оставленная закваска, точный вес в граммах")
            .apply { performTextClearance(); performTextInput("43") }
        rule.runOnIdle { history.value = past }
        rule.onNodeWithText("записать вес").performClick()
        masses(43, 100, 50)
        save(43, 100, 50)
    }
    @Test fun `cold preset survives restoration and saves fridge masses`() {
        val restoration = show()
        choose("1 : 5 : 5 · в холод")
        masses(50, 250, 250)
        restoration.emulateSavedInstanceStateRestore()
        chip("1 : 5 : 5 · в холод").assertIsSelected()
        rule.onNodeWithContentDescription("Где стоит, Холодильник").assertIsSelected()
        save(50, 250, 250, StorageLocation.FRIDGE)
    }
    @Test fun `cold preset replaces an open exact flour editor before save and restoration`() {
        val restoration = show()
        rule.onNodeWithContentDescription("Добавленная мука: 100 г").performScrollTo().performClick()
        val flourEditor = rule.onNodeWithContentDescription("Добавленная мука, точный вес в граммах")
        flourEditor.assertTextEquals("100")
        choose("1 : 5 : 5 · в холод")
        flourEditor.assertDoesNotExist()
        masses(50, 250, 250)
        save(50, 250, 250, StorageLocation.FRIDGE)
        restoration.emulateSavedInstanceStateRestore()
        flourEditor.assertDoesNotExist()
        masses(50, 250, 250)
        chip("1 : 5 : 5 · в холод").assertIsSelected()
        rule.onNodeWithContentDescription("Добавленная мука: 250 г").performScrollTo().performClick()
        flourEditor.assertTextEquals("250")
        rule.onNodeWithText("записать вес").performScrollTo().performClick()
        save(50, 250, 250, StorageLocation.FRIDGE)
    }
    @Test fun `reapplying unchanged preset closes every exact editor without erasing the note`() {
        val restoration = show()
        choose("1 : 5 : 5 · в холод")
        rule.onNode(hasSetTextAction()).performScrollTo().performTextInput("Своя заметка")
        val values = listOf("Оставленная закваска" to 50, "Добавленная мука" to 250, "Добавленная вода" to 250)
        values.forEach { (label, grams) ->
            rule.onNodeWithContentDescription("$label: $grams г").performScrollTo().performClick()
            rule.onNodeWithContentDescription("$label, точный вес в граммах").apply {
                performScrollTo(); performTextClearance(); performTextInput("43")
            }
        }
        choose("1 : 5 : 5 · в холод")
        values.forEach { (label, _) ->
            rule.onNodeWithContentDescription("$label, точный вес в граммах").assertDoesNotExist()
        }
        masses(50, 250, 250)
        restoration.emulateSavedInstanceStateRestore()
        values.forEach { (label, grams) ->
            rule.onNodeWithContentDescription("$label, точный вес в граммах").assertDoesNotExist()
            rule.onNodeWithContentDescription("$label: $grams г").performScrollTo().performClick()
            rule.onNodeWithContentDescription("$label, точный вес в граммах").assertTextEquals(grams.toString())
            rule.onNodeWithText("записать вес").performScrollTo().performClick()
        }
        rule.onNodeWithText("Вписать в дневник").performScrollTo().performClick()
        rule.runOnIdle {
            assertThat(saved).isEqualTo(listOf(50, 250, 250, StorageLocation.FRIDGE, "Своя заметка", null))
        }
    }
    @Test fun `pending manual buffer survives restoration after a preset and ignores late history`() {
        val restoration = show()
        choose("1 : 1 : 1")
        rule.onNodeWithContentDescription("Оставленная закваска: 50 г").performScrollTo().performClick()
        val editor = rule.onNodeWithContentDescription("Оставленная закваска, точный вес в граммах")
        editor.performTextClearance()
        editor.performTextInput("43")
        restoration.emulateSavedInstanceStateRestore()
        rule.runOnIdle { history.value = past }
        editor.assertTextEquals("43")
        rule.onNodeWithText("записать вес").performScrollTo().performClick()
        masses(43, 50, 50)
        save(43, 50, 50)
    }
    @Test fun `ordinary ratio preserves explicitly chosen fridge`() {
        show()
        rule.onNodeWithContentDescription("Где стоит, Холодильник").performScrollTo().performClick()
        choose("1 : 2 : 2")
        masses(50, 100, 100)
        save(50, 100, 100, StorageLocation.FRIDGE)
    }
    @Test fun `incompatible ratios are disabled instead of clipped`() {
        show()
        exactStarter(152)
        chip("1 : 2 : 2").assertIsNotEnabled()
        chip("1 : 5 : 5 · в холод").assertIsNotEnabled()
        choose("1 : 1 : 1")
        masses(152, 150, 150)
        save(152, 150, 150)
    }
    @Test fun `changing cold location removes misleading selected cold preset`() {
        show()
        choose("1 : 5 : 5 · в холод")
        rule.onNodeWithContentDescription("Где стоит, Кухня").performScrollTo().performClick()
        chip("1 : 5 : 5 · в холод").assertIsNotSelected()
        save(50, 250, 250)
    }
}
