package com.polinalinen.madre.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.ui.kit.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w412dp-h915dp-mdpi")
class PaperKitInteractionTest {
    @get:Rule val rule = createComposeRule()

    @Test fun customSwitchChangesStateAndRemainsAnAccessibleSwitch() {
        var checked = false
        rule.setContent { RazvorotTheme(force = Atmosphere.NIGHT) {
            var state by remember { mutableStateOf(false) }
            PaperSwitch(state, { state = it; checked = it })
        } }
        rule.onNode(isToggleable()).assertIsOff().performClick().assertIsOn()
        assertThat(checked).isTrue()
    }

    @Test fun disabledSwitchNeverInvokesItsCallback() {
        var calls = 0
        rule.setContent { RazvorotTheme(force = Atmosphere.DAY) {
            PaperSwitch(false, { calls++ }, enabled = false)
        } }
        rule.onNode(isToggleable()).assertIsNotEnabled()
        assertThat(calls).isEqualTo(0)
    }

    @Test fun shortManropeLabelsKeepOriginalSpacesAndActuallyWidenWordBoundaries() {
        val labels=listOf("По часам","Как книга","По муке")
        val original=mutableMapOf<String,androidx.compose.ui.text.TextLayoutResult>()
        val spaced=mutableMapOf<String,androidx.compose.ui.text.TextLayoutResult>()
        rule.setContent{RazvorotTheme(force=Atmosphere.DAWN){Column {
            labels.forEach{label->
                androidx.compose.material3.Text(label,style=PaperType.caption,onTextLayout={original[label]=it})
                androidx.compose.material3.Text(paperControlText(label),style=PaperType.caption,onTextLayout={spaced[label]=it})
            }
        }}}
        rule.runOnIdle{labels.forEach{label->
            val before=original.getValue(label);val after=spaced.getValue(label)
            val space=label.indexOf(' ')
            assertThat(after.layoutInput.text.text).isEqualTo(label)
            assertThat(after.getBoundingBox(space).width).isGreaterThan(before.getBoundingBox(space).width)
            assertThat(after.getBoundingBox(0).width).isWithin(.01f).of(before.getBoundingBox(0).width)
        }}
    }

    @Test fun sharedSegmentsAndChipsKeepMultiwordSemanticsAndRealSelection() {
        var segment=0;var chip=false
        rule.setContent{RazvorotTheme(force=Atmosphere.NIGHT){
            var selected by remember{mutableIntStateOf(0)}
            var checked by remember{mutableStateOf(false)}
            Column {
                PaperSegmented(listOf("Как книга","По главам"),selected,{selected=it;segment=it})
                PaperChip("Мои рецепты",checked,{checked=!checked;chip=checked})
            }
        }}
        rule.onNodeWithText("Как книга").assertIsSelected()
        rule.onNodeWithText("По главам").assertIsNotSelected().performClick().assertIsSelected()
        rule.onNodeWithText("Мои рецепты").assertIsNotSelected().performClick().assertIsSelected()
        rule.runOnIdle{assertThat(segment).isEqualTo(1);assertThat(chip).isTrue()}
        rule.onNodeWithText("Каккнига").assertDoesNotExist()
    }

    @Test fun actualReadingSliderUpdatesPersistedTextSizeWithinItsRange() {
        val context=androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        context.getSharedPreferences("kit15-reading",0).edit().clear().commit()
        val prefs=ReadingPreferences(context,"kit15-reading")
        rule.setContent{RazvorotTheme(prefs,Atmosphere.DAWN){com.polinalinen.madre.ui.razvorot.ReadingSettings(prefs){}}}
        rule.onNodeWithTag("text-size").performScrollTo()
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){assertThat(it(20f)).isTrue()}
        rule.runOnIdle{
            assertThat(prefs.textSize).isEqualTo(20f)
            assertThat(ReadingPreferences(context,"kit15-reading").textSize).isEqualTo(20f)
        }
        rule.onNodeWithTag("text-size").assertRangeInfoEquals(androidx.compose.ui.semantics.ProgressBarRangeInfo(20f,14f..22f,7))
    }

    @Test fun actualQuantitySliderChangesRecipeFactorAndPreservesIntegerBounds() {
        val context=androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val recipe=kotlinx.coroutines.runBlocking{com.polinalinen.madre.data.repository.RecipeRepository(context).getRecipes().first()}
        var factor=2.0
        rule.setContent{RazvorotTheme(force=Atmosphere.DAWN){
            var value by remember{mutableDoubleStateOf(2.0)}
            com.polinalinen.madre.ui.razvorot.BookPortionsSheet(recipe,value,{value=it;factor=it},{})
        }}
        val slider=rule.onNodeWithTag("quantity-scale").performScrollTo()
        slider.performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress){assertThat(it(3f)).isTrue()}
        rule.runOnIdle{assertThat(factor).isEqualTo(3.0)}
        slider.assertRangeInfoEquals(androidx.compose.ui.semantics.ProgressBarRangeInfo(3f,1f..5f,3))
        slider.performTouchInput{click(androidx.compose.ui.geometry.Offset(width*.05f,center.y))}
        rule.runOnIdle{assertThat(factor).isEqualTo(1.0)}
        slider.performTouchInput{click(androidx.compose.ui.geometry.Offset(width*.95f,center.y))}
        rule.runOnIdle{assertThat(factor).isEqualTo(5.0)}
    }

    @Test fun aPaperFieldRetainsRealTextEditingAndItsError() {
        var edited = ""
        rule.setContent { RazvorotTheme(force = Atmosphere.DUSK) {
            var text by remember { mutableStateOf("") }
            PaperField(text, { text = it; edited = it }, label = "Имя", error = "Проверьте имя")
        } }
        rule.onNode(hasSetTextAction()).performTextInput("Мадре")
        rule.onNodeWithText("Мадре").assertExists()
        rule.onNodeWithText("Проверьте имя").assertExists()
        assertThat(edited).isEqualTo("Мадре")
    }
    @Test fun bakingAliceEntryActuallyInvokesItsRouteCallback() {
        var opened = false
        val recipe = com.polinalinen.madre.model.Recipe("fixture", "Демонстрационная выпечка", "", "", emptyMap(),
            listOf(com.polinalinen.madre.model.TimelineStep(com.polinalinen.madre.model.StepType.ACTION, "Замес", "Текст тестового шага", 5)))
        rule.setContent { RazvorotTheme(force = Atmosphere.NIGHT) {
            com.polinalinen.madre.ui.razvorot.BakingPaper(
                com.polinalinen.madre.model.BakingSession(7, recipe, 0, 0L, 0L, scaleFactor = 1.0),
                120L, {}, {}, {}, {}, onOpenAlice = { opened = true })
        } }
        rule.onNodeWithText("Алиса").assertIsDisplayed().performClick()
        assertThat(opened).isTrue()
    }
}
