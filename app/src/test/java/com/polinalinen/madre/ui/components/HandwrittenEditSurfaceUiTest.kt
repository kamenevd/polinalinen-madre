package com.polinalinen.madre.ui.components

import com.polinalinen.madre.ui.kit.*
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.ui.kit.RazvorotTheme as MadreTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Cycle 16: рецепт переехал с Column(verticalScroll) на LazyColumn, и блок
 * «рецепт целиком» вместе с правкой от руки стал выкидываться из композиции,
 * стоит ему уехать за край экрана.
 *
 * Раньше этого случиться не могло: Column композировал всю страницу разом, и
 * включённый карандаш жил ровно столько, сколько открыт экран. Теперь между
 * «включил правку» и «пишу» помещается прокрутка — и если состояние режима
 * держать обычным remember, карандаш выключается сам, молча, посреди работы.
 *
 * Здесь проверяется именно это: пережил ли режим правки уход блока из
 * композиции. Тест ходит через настоящий LazyColumn с высокой заглушкой сверху,
 * а не подменяет прокрутку вызовом dispose — потому что ломается всё как раз на
 * настоящей прокрутке.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi")
class HandwrittenEditSurfaceUiTest {

    @get:Rule
    val rule = createComposeRule()

    private companion object {
        const val OFF = "редактировать от руки"
        const val ON = "готово — вписать в книгу"
    }

    /** Заглушка заведомо выше экрана (640dp): прокрутка вниз выносит её из композиции. */
    private lateinit var state: androidx.compose.foundation.lazy.LazyListState

    private fun openInLazyColumn() {
        rule.setContent {
            MadreTheme {
                state = rememberLazyListState()
                LazyColumn(state = state) {
                    item(key = "tall-filler") {
                        Box(Modifier.fillMaxWidth().height(2000.dp))
                    }
                    item(key = "full-recipe") {
                        // The retained editor still works when explicitly tested enabled;
                        // the shipping facade is separately required to be read-only.
                        HandwrittenEditLayer(recipeId = "borodinsky",canEdit=true) {
                            Text("Рецепт целиком")
                        }
                    }
                }
            }
        }
    }

    @Test fun disabledShippingFacadeHidesEveryEditActionAndPreservesStoredStrokes() {
        val context=androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        val id="read-only-owner15"
        val strokes=listOf(listOf(androidx.compose.ui.geometry.Offset(.1f,.2f),androidx.compose.ui.geometry.Offset(.6f,.7f)))
        runBlocking{HandwrittenEdit.saveStrokes(context,id,strokes)}
        val file=java.io.File(context.filesDir,HandwrittenEdit.strokesFileNameFor(id));val before=file.readBytes()
        rule.setContent{MadreTheme{HandwrittenEditSurface(id){Text("Сохранённый рецепт доступен")}}}
        rule.onNodeWithText("Сохранённый рецепт доступен").assertIsDisplayed()
        rule.onNodeWithText(OFF).assertDoesNotExist();rule.onNodeWithText(ON).assertDoesNotExist()
        rule.onNodeWithContentDescription("Убрать последний штрих").assertDoesNotExist()
        rule.waitForIdle()
        org.junit.Assert.assertArrayEquals(before,file.readBytes())
        org.junit.Assert.assertEquals(strokes,runBlocking{HandwrittenEdit.loadStrokes(context,id)})
    }

    private fun scrollTo(index: Int) {
        runBlocking { state.scrollToItem(index) }
        rule.waitForIdle()
    }

    @Test
    fun `режим правки переживает уход блока из композиции при прокрутке`() {
        openInLazyColumn()

        scrollTo(1)
        rule.onNodeWithText(OFF).assertIsDisplayed()
        rule.onNodeWithText(OFF).performClick()
        rule.onNodeWithText(ON).assertIsDisplayed()

        // Уезжаем к началу страницы: блок правки выходит из композиции целиком.
        scrollTo(0)
        rule.onNodeWithText(ON).assertDoesNotExist()

        // Возвращаемся — карандаш должен остаться включённым.
        scrollTo(1)
        rule.onNodeWithText(ON).assertIsDisplayed()
    }

    @Test
    fun `выключенный режим правки остаётся выключенным после прокрутки`() {
        openInLazyColumn()

        scrollTo(1)
        rule.onNodeWithText(OFF).assertIsDisplayed()

        scrollTo(0)
        scrollTo(1)

        rule.onNodeWithText(OFF).assertIsDisplayed()
    }
}
