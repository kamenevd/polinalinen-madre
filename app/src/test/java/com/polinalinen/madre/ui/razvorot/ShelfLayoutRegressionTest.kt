package com.polinalinen.madre.ui.razvorot

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.polinalinen.madre.shelf.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],qualifiers="ru-w412dp-h915dp-mdpi")
class ShelfLayoutRegressionTest {
    @get:Rule val rule=createComposeRule()
    @Test fun actualShelfReservesRibbonAndScrollsWholeLedgerAndFooterAtEveryScale() {
        val prefs=ReadingPreferences(ApplicationProvider.getApplicationContext<Context>(),"shelf-layout-audit")
        var name by mutableStateOf("Пекарь")
        var scale by mutableFloatStateOf(1f)
        var theme by mutableStateOf(Atmosphere.DAWN)
        val chapter="Хлебушек домашний — длинное название главы рецепта, которое переносится естественно"
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f,scale)) {
                RazvorotTheme(prefs,theme) {key(name,scale,theme) {
                    Column(Modifier.size(412.dp,915.dp)) {
                        Box(Modifier.weight(1f)){ShelfPaper(listOf(ShelfMember("me",name,true),ShelfMember("family","Семья",false)),listOf(ShelfLedgerRow(1L,"Пекарь",chapter,"me","23 октября")),"Проверка полки",emptyList(),false,{},{})}
                        SpineBar(3){}
                    }
                }}
            }
        }
        for(t in listOf(Atmosphere.DAWN,Atmosphere.NIGHT))for(s in listOf(1f,1.3f,2f))for(n in listOf("Пекарь","Александра Константиновна Каменева")) {
            rule.runOnIdle{name=n;scale=s;theme=t}
            val ribbon=rule.onNodeWithTag("shelf-own-ribbon",useUnmergedTree=true).getUnclippedBoundsInRoot()
            val area=rule.onNodeWithTag("shelf-own-title-area",useUnmergedTree=true).getUnclippedBoundsInRoot()
            val text=rule.onNodeWithText(n,useUnmergedTree=true).getUnclippedBoundsInRoot()
            assertThat(ribbon.bottom.value-ribbon.top.value).isEqualTo(44f)
            assertWithMessage("$t scale=$s name=$n reserved=$area ribbon=$ribbon").that(area.top.value-ribbon.bottom.value).isAtLeast(8f)
            assertThat(text.top.value).isAtLeast(ribbon.bottom.value+8f-.5f)
            val layouts=mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(n,useUnmergedTree=true).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult){it(layouts)}
            assertThat(layouts.single().lineCount).isEqualTo(1)
            assertThat(layouts.single().layoutInput.style.fontSize.value).isAtLeast(11f)
            if(n=="Пекарь") {assertThat(layouts.single().hasVisualOverflow).isFalse();assertThat(layouts.single().isLineEllipsized(0)).isFalse()}
            else assertThat(layouts.single().isLineEllipsized(0)).isTrue()
            rule.onNodeWithTag(FamilyShelf.LIST_TAG).performScrollToNode(hasTestTag("shelf-caption"))
            rule.onNodeWithText(FamilyShelf.CAPTION).assertIsDisplayed()
            assertThat(FamilyShelf.CAPTION).startsWith("Ваша книга —")
            val caption=mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(FamilyShelf.CAPTION).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult){it(caption)}
            assertThat(caption.single().hasVisualOverflow).isFalse()
            rule.onNodeWithTag(FamilyShelf.LIST_TAG).performScrollToNode(hasTestTag("shelf-alice-footer"))
            rule.onAllNodesWithText("Подключить Алису").assertCountEquals(1)
            val footer=rule.onNodeWithTag("shelf-alice-footer").getUnclippedBoundsInRoot()
            val row=rule.onNodeWithTag("shelf-ledger-1-me").getUnclippedBoundsInRoot()
            val viewport=rule.onNodeWithTag(FamilyShelf.LIST_TAG).getUnclippedBoundsInRoot()
            assertThat(row.bottom.value).isAtMost(footer.top.value+.5f)
            assertThat(row.top.value).isAtLeast(viewport.top.value-.5f)
            assertThat(footer.bottom.value).isAtMost(viewport.bottom.value+.5f)
            val body=mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(chapter).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult){it(body)}
            assertThat(body.single().hasVisualOverflow).isFalse()
            assertThat(row.bottom.value-row.top.value).isAtLeast(body.single().size.height.toFloat())
            rule.onNodeWithTag("nav-3").assertIsSelected()
        }
    }
}
