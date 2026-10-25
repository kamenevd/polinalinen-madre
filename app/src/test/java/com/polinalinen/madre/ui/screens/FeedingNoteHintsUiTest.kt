package com.polinalinen.madre.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.core.app.ActivityOptionsCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.db.entities.FeedingEntity
import com.polinalinen.madre.data.db.entities.StorageLocation
import com.polinalinen.madre.ui.kit.RazvorotTheme
import com.polinalinen.madre.utils.PhotoStore
import com.polinalinen.madre.viewmodel.FeedingSaveState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi", application = MadreApplication::class)
class FeedingNoteHintsUiTest {
    @get:Rule val rule = createComposeRule()
    private val hints = listOf("пахнет яблоками", "крупные пузыри", "кислит", "жидковата")
    private val state = mutableStateOf<FeedingSaveState>(FeedingSaveState.Idle)
    private val saves = mutableListOf<List<Any?>>()
    private var picked: Uri? = null
    private var launches = 0
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(code: Int, contract: ActivityResultContract<I, O>,
            input: I, options: ActivityOptionsCompat?) {
            launches++
            check(picked != null) { "Only the explicit synthetic gallery action may launch" }
            dispatchResult(code, picked)
        }
    }
    private fun show(): StateRestorationTester = StateRestorationTester(rule).also { restoration ->
        restoration.setContent {
            CompositionLocalProvider(LocalActivityResultRegistryOwner provides object : ActivityResultRegistryOwner {
                override val activityResultRegistry = registry
            }) {
                RazvorotTheme { FeedingFormScreen(
                    onSave = { s, f, w, l, n, p -> saves += listOf(s, f, w, l, n, p) },
                    onBack = {}, saveState = state.value, priorHydrationPercent = 72,
                    lastFeeding = FeedingEntity(sourdoughConfigId = 1, retainedStarterGrams = 47,
                        flourGrams = 113, waterGrams = 59, storageLocation = StorageLocation.FRIDGE,
                        notes = "НЕ КОПИРОВАТЬ",
                        photoPath = "feeding_photos/old.jpg"),
                ) }
            }
        }
    }
    private fun note() = rule.onNode(hasSetTextAction())
    private fun assertNote(expected: String) {
        assertThat(note().fetchSemanticsNode().config[SemanticsProperties.EditableText].text).isEqualTo(expected)
    }
    private fun click(hint: String) {
        rule.onNodeWithText("Вписать в дневник").performScrollTo()
        rule.onNodeWithText("+ $hint").performScrollTo().assertIsDisplayed().performClick()
    }
    private fun save() = rule.onNodeWithText("Вписать в дневник").performScrollTo().performClick()

    @Test fun `four exact labels insert only clicked phrases with no automatic save or observation`() {
        show()
        assertNote("")
        hints.forEach { hint ->
            val chip = rule.onNodeWithText("+ $hint")
            chip.assertExists().assertHasClickAction()
            assertThat(chip.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role)).isEqualTo(Role.Button)
        }
        rule.runOnIdle { assertThat(launches).isEqualTo(0); assertThat(saves).isEmpty() }
        var expected = ""
        hints.forEach { hint ->
            click(hint)
            expected += (if (expected.isEmpty()) "" else "\n") + hint
            assertNote(expected)
            rule.runOnIdle { assertThat(saves).isEmpty() }
        }
        click("кислит")
        assertNote("$expected\nкислит")
        save()
        rule.runOnIdle { assertThat(saves).containsExactly(
            listOf(47, 113, 59, StorageLocation.KITCHEN, "$expected\nкислит", null)) }
    }

    @Test fun `typed and restored whitespace prefixes stay verbatim and save keeps existing trim contract`() {
        val restoration = show()
        val prefix = "  Своя строка\n\nещё строка  \n"
        note().performScrollTo().performTextInput(prefix)
        restoration.emulateSavedInstanceStateRestore()
        assertNote(prefix)
        click("жидковата")
        assertNote(prefix + "жидковата")
        restoration.emulateSavedInstanceStateRestore()
        assertNote(prefix + "жидковата")
        save()
        rule.runOnIdle { assertThat(saves.single()[4]).isEqualTo((prefix + "жидковата").trim()) }
    }

    @Test fun `saving disables hints and blank note still saves null`() {
        show()
        note().performScrollTo().performTextInput("  \n ")
        rule.runOnIdle { state.value = FeedingSaveState.Saving }
        hints.forEach { rule.onNodeWithText("+ $it").assertIsNotEnabled() }
        assertNote("  \n ")
        rule.runOnIdle { assertThat(saves).isEmpty(); state.value = FeedingSaveState.Idle }
        save()
        rule.runOnIdle { assertThat(saves.single()[4]).isNull() }
    }

    @Test fun `hint preserves actual attached photo manual grams location hydration and retry draft across restore`() {
        val restoration = show()
        rule.onNodeWithContentDescription("Оставленная закваска: 47 г").performScrollTo().performClick()
        rule.onNodeWithContentDescription("Оставленная закваска, точный вес в граммах").apply {
            performTextClearance(); performTextInput("61")
        }
        rule.onNodeWithText("записать вес").performClick()
        rule.onNodeWithContentDescription("Где стоит, Холодильник").performScrollTo().performClick()
        val hydration = rule.onNode(hasContentDescription("Гидратация после кормления:", substring = true))
            .fetchSemanticsNode().config[SemanticsProperties.ContentDescription].single()
        val app = ApplicationProvider.getApplicationContext<MadreApplication>()
        val source = File.createTempFile("hint-fixture-", ".jpg", app.cacheDir)
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        bitmap.recycle()
        picked = Uri.fromFile(source)
        rule.onNodeWithText("вклеить фотокарточку").performScrollTo().performClick()
        rule.onNodeWithText("Галерея").performSemanticsAction(SemanticsActions.OnClick) { assertThat(it()).isTrue() }
        rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription("Фотокарточка, как она ляжет в книгу")
            .fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Без оформления").performScrollTo()
            .performSemanticsAction(SemanticsActions.OnClick) { assertThat(it()).isTrue() }
        rule.waitUntil(10_000) { rule.onAllNodesWithText("заменить фотокарточку").fetchSemanticsNodes().isNotEmpty() }
        note().performScrollTo().performTextInput("Свой текст")
        save()
        val before = rule.runOnIdle { saves.single() }
        val photo = before[5] as String
        assertThat(PhotoStore.resolve(app, photo).isFile).isTrue()
        val photoBytes = PhotoStore.resolve(app, photo).readBytes()
        rule.runOnIdle { state.value = FeedingSaveState.Error("Память занята") }
        click("крупные пузыри")
        rule.runOnIdle { assertThat(saves).hasSize(1) }
        restoration.emulateSavedInstanceStateRestore()
        assertNote("Свой текст\nкрупные пузыри")
        rule.onNodeWithContentDescription(hydration).assertExists()
        rule.onNodeWithContentDescription("Где стоит, Холодильник").assertIsSelected()
        rule.onNodeWithText("Ошибка сохранения: Память занята").performScrollTo().assertExists()
        rule.onNodeWithText("заменить фотокарточку").assertExists()
        save()
        rule.runOnIdle {
            assertThat(before.take(4)).isEqualTo(listOf(61, 113, 59, StorageLocation.FRIDGE))
            assertThat(saves.last()).isEqualTo(before.toMutableList().apply { this[4] = "Свой текст\nкрупные пузыри" })
            assertThat(launches).isEqualTo(1)
        }
        assertThat(PhotoStore.resolve(app, photo).readBytes()).isEqualTo(photoBytes)
    }
}
