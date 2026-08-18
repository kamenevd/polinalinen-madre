package com.polinalinen.madre.ui.screens

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.db.entities.StorageLocation
import com.polinalinen.madre.viewmodel.FeedingSaveState
import com.polinalinen.madre.ui.theme.MadreTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Cycle 26: форма кормления спрашивает три массы и НИ РАЗУ — гидратацию.
 * Гидратация выводится из названных граммов, и поля для неё на странице нет:
 * поле означало бы, что книга готова записать число, противоречащее её же
 * арифметике.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi", application = MadreApplication::class)
class FeedingFormMassesUiTest {

    @get:Rule val rule = createComposeRule()

    private data class Saved(
        val starter: Int,
        val flour: Int,
        val water: Int,
        val location: StorageLocation,
        val note: String?,
        val photoPath: String?,
    )

    private fun show(
        priorHydrationPercent: Int? = 50,
        saveState: FeedingSaveState = FeedingSaveState.Idle,
        onSaved: (Saved) -> Unit = {},
        onBack: () -> Unit = {},
    ) {
        rule.setContent {
            MadreTheme {
                FeedingFormScreen(
                    onSave = { starter, flour, water, location, note, photo ->
                        onSaved(Saved(starter, flour, water, location, note, photo))
                    },
                    onBack = onBack,
                    saveState = saveState,
                    priorHydrationPercent = priorHydrationPercent,
                )
            }
        }
    }

    @Test fun `there is no editable hydration control`() {
        show()
        // Ни поля, ни кнопки подтверждения — гидратация только показывается.
        rule.onAllNodesWithText("Подтвердить").assertCountEquals(0)
        rule.onAllNodesWithText("Подтверждено").assertCountEquals(0)
        rule.onAllNodesWithText("Гидратация — от 1 до 200%").assertCountEquals(0)
        // Единственное поле ввода на странице — заметка о кормлении.
        rule.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        rule.onNodeWithContentDescription("Гидратация после кормления: 50 процентов, посчитана книгой")
            .performScrollTo()
            .assertExists()
    }

    @Test fun `three gram controls carry sliders, steps and exact values`() {
        show()
        listOf("Оставленная закваска", "Добавленная мука", "Добавленная вода").forEach { label ->
            rule.onNodeWithContentDescription("$label, граммы").performScrollTo().assertExists()
        }
        rule.onNodeWithContentDescription("Оставленная закваска: 50 г").assertExists()
        rule.onNodeWithContentDescription("Добавленная мука: 100 г").assertExists()
        rule.onNodeWithContentDescription("Добавленная вода: 50 г").assertExists()
        // По кнопке «+5 г» у каждой массы — своя.
        rule.onAllNodesWithText("+5 г").assertCountEquals(3)
        rule.onAllNodesWithText("−5 г").assertCountEquals(3)
    }

    @Test fun `plus five changes only its own mass`() {
        show()
        rule.onAllNodesWithText("+5 г")[0].performScrollTo().performClick()
        rule.onNodeWithContentDescription("Оставленная закваска: 55 г").assertExists()
        rule.onNodeWithContentDescription("Добавленная мука: 100 г").assertExists()
        rule.onNodeWithContentDescription("Добавленная вода: 50 г").assertExists()
    }

    /** Кухонные весы показывают 47 г — ползунок такого не наберёт, а книга обязана. */
    @Test fun `tapping the value opens exact numeric entry`() {
        var saved: Saved? = null
        show(onSaved = { saved = it })
        rule.onNodeWithContentDescription("Оставленная закваска: 50 г").performScrollTo().performClick()

        val field = rule.onNodeWithContentDescription("Оставленная закваска, точный вес в граммах")
        field.performTextClearance()
        field.performTextInput("47")
        rule.onNodeWithText("записать вес").performClick()

        rule.onNodeWithContentDescription("Оставленная закваска: 47 г").assertExists()
        rule.onNodeWithText("Вписать в дневник").performScrollTo().performClick()
        assertThat(saved).isEqualTo(Saved(47, 100, 50, StorageLocation.KITCHEN, null, null))
    }

    @Test fun `saving hands over three masses without a hydration argument`() {
        var saved: Saved? = null
        show(onSaved = { saved = it })
        rule.onNodeWithText("Вписать в дневник").performScrollTo().performClick()
        assertThat(saved).isEqualTo(Saved(50, 100, 50, StorageLocation.KITCHEN, null, null))
    }

    /** Первое кормление называет источник стартовой гидратации вслух. */
    @Test fun `the first feeding names the declared initial hydration`() {
        show(priorHydrationPercent = null)
        rule.onNodeWithText(
            "Считаем от стартовой гидратации 50% — посчитанных кормлений в дневнике ещё нет"
        ).performScrollTo().assertExists()
    }

    /** Дальше книга считает от сохранённой, а не от буклета. */
    @Test fun `later feedings count from the saved hydration`() {
        show(priorHydrationPercent = 72)
        rule.onNodeWithText("Считаем от прошлой гидратации 72%").performScrollTo().assertExists()
        // 50 г закваски при 72% — это 29.07 г муки и 20.93 г воды; плюс 100 г
        // муки и 50 г воды даёт 55%.
        rule.onNodeWithContentDescription("Гидратация после кормления: 55 процентов, посчитана книгой")
            .assertExists()
    }

    @Test fun `save announcement is announced and save button is disabled while saving`() {
        show(saveState = FeedingSaveState.Saving)

        rule.onNodeWithText("Сохраняется запись").performScrollTo().assertExists()
        rule.onNodeWithText("Вписать в дневник")
            .performScrollTo()
            .assertIsNotEnabled()
    }

    @Test fun `back is ignored while saving`() {
        var returned = false
        show(
            saveState = FeedingSaveState.Saving,
            onBack = { returned = true },
        )

        rule.onNodeWithText("← ДНЕВНИК").performClick()
        assertThat(returned).isFalse()
    }

    @Test fun `back works while idle`() {
        var returned = false
        show(onBack = { returned = true })

        rule.onNodeWithText("← ДНЕВНИК").performClick()
        assertThat(returned).isTrue()
    }

    @Test fun `error announcement stays retryable`() {
        show(saveState = FeedingSaveState.Error("База временно недоступна"))

        rule.onNodeWithText("Ошибка сохранения: База временно недоступна")
            .performScrollTo()
            .assertExists()
        rule.onNodeWithText("Вписать в дневник")
            .performScrollTo()
            .assertExists()
    }
}
