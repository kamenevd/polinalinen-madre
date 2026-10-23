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
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.ui.screens.bakeWord
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],qualifiers="ru-w360dp-h780dp-xhdpi",application=MadreApplication::class)
class ShelfReviewTest {
    @get:Rule val rule=createComposeRule()
    @Test fun bothCountersUseRussianPlurals() {
        val counts=listOf(0,1,2,5,11,12,13,14,21,22,25)
        val bakes=listOf("выпечек","выпечка","выпечки","выпечек","выпечек","выпечек","выпечек","выпечек","выпечка","выпечки","выпечек")
        val books=listOf("книг","книга","книги","книг","книг","книг","книг","книг","книга","книги","книг")
        counts.forEachIndexed { i,n ->
            assertThat(bakeWord(n)).isEqualTo(bakes[i])
            assertThat(bookWord(n)).isEqualTo(books[i])
        }
    }
    @Test fun rotatedNamesStayOneLineInBothThemesAndLargeType() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val prefs=ReadingPreferences(context,"shelf-review-test")
        var name by mutableStateOf("Пекарь")
        var scale by mutableFloatStateOf(1f)
        var theme by mutableStateOf(Atmosphere.DAWN)
        rule.setContent {
            CompositionLocalProvider(LocalDensity provides Density(2f,scale)) {
                RazvorotTheme(prefs,theme) { Box(Modifier.size(56.dp,180.dp)){SpineTitle(name)} }
            }
        }
        for(t in listOf(Atmosphere.DAWN,Atmosphere.NIGHT))for(s in listOf(1f,1.3f,2f))for(n in listOf("Пекарь","Семья","Александра Константиновна")) {
            rule.runOnIdle{name=n;scale=s;theme=t}
            val layouts=mutableListOf<TextLayoutResult>()
            rule.onNodeWithText(n).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult){it(layouts)}
            val result=layouts.single()
            assertThat(result.lineCount).isEqualTo(1)
            if(n.length<10) {
                assertThat(result.isLineEllipsized(0)).isFalse()
                assertWithMessage("$n theme=$t scale=$s size=${result.size} width=${result.didOverflowWidth} height=${result.didOverflowHeight} naturalHeight=${result.multiParagraph.height}").that(result.hasVisualOverflow).isFalse()
            } else assertThat(result.isLineEllipsized(0)).isTrue()
        }
    }
}
