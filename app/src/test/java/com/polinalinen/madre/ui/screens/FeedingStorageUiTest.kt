package com.polinalinen.madre.ui.screens

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.polinalinen.madre.MadreApplication
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
class FeedingStorageUiTest {
    @get:Rule val rule = createComposeRule()

    private data class Saved(
        val starter: Int, val flour: Int, val water: Int,
        val location: StorageLocation, val note: String?, val photo: String?,
    )

    private val saves = mutableListOf<Saved>()

    private fun show(): StateRestorationTester = StateRestorationTester(rule).also { restoration ->
        restoration.setContent {
            RazvorotTheme {
                FeedingFormScreen(
                    onSave = { starter, flour, water, location, note, photo ->
                        saves += Saved(starter, flour, water, location, note, photo)
                    },
                    onBack = {},
                )
            }
        }
    }

    private fun option(label: String) = rule.onNodeWithContentDescription("Где стоит, $label")

    private fun assertChoice(selected: String, other: String) {
        rule.onNodeWithText("Где стоит", ignoreCase = true).assertExists()
        option(selected).assertIsSelected()
        option(other).assertIsNotSelected()
    }

    private fun save(expected: StorageLocation) {
        rule.onNodeWithText("Вписать в дневник").performScrollTo().performClick()
        rule.runOnIdle {
            assertThat(saves).containsExactly(Saved(50, 100, 50, expected, null, null))
        }
    }

    @Test fun `kitchen is default and reselecting it does not cycle`() {
        show()
        assertChoice("Кухня", "Холодильник")
        option("Кухня").performScrollTo().performClick()
        assertChoice("Кухня", "Холодильник")
        save(StorageLocation.KITCHEN)
    }

    @Test fun `fridge is explicit and reselecting saves the exact fridge enum`() {
        show()
        option("Холодильник").performScrollTo().performClick()
        assertChoice("Холодильник", "Кухня")
        option("Холодильник").performClick()
        assertChoice("Холодильник", "Кухня")
        rule.runOnIdle { assertThat(saves).isEmpty() }
        save(StorageLocation.FRIDGE)
    }

    @Test fun `switching back to kitchen saves kitchen`() {
        show()
        option("Холодильник").performScrollTo().performClick()
        option("Кухня").performClick()
        assertChoice("Кухня", "Холодильник")
        save(StorageLocation.KITCHEN)
    }

    @Test fun `fridge selection survives saved state restoration and is saved`() {
        val restoration = show()
        option("Холодильник").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        option("Холодильник").performScrollTo()
        assertChoice("Холодильник", "Кухня")
        save(StorageLocation.FRIDGE)
    }

    @Test fun `both choices have radio semantics labels and finger targets`() {
        show()
        option("Кухня").performScrollTo()
        listOf("Кухня", "Холодильник").forEach { label ->
            val node = option(label).fetchSemanticsNode()
            assertThat(node.config.getOrNull(SemanticsProperties.Role)).isEqualTo(Role.RadioButton)
            assertWithMessage("storage option height, dp: $label")
                .that(node.size.height / rule.density.density).isAtLeast(48f)
        }
        assertChoice("Кухня", "Холодильник")
    }
}
