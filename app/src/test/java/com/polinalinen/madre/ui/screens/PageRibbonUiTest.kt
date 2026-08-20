package com.polinalinen.madre.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.polinalinen.madre.sourdough.GrowthPhase
import com.polinalinen.madre.ui.theme.AppColors
import com.polinalinen.madre.ui.theme.MadreTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Cycle 18: ляссе на первой полосе — украшение, а не вторая дорога.
 *
 * Лента выпечки вела к таймеру мимо талона, и не к той выпечке, на которую
 * человек смотрит, а к ближайшей по времени. Mood-ляссе решало за человека
 * само: на пике открывало рецепт попроще, иначе кормление. Ни одна из двух
 * ничем не выдавала, что вообще нажимается.
 *
 * Проверяется [PageRibbon] — тот самый composable, который зовёт первая
 * полоса, — а не экран целиком: экрану для активной выпечки нужны Room,
 * ViewModel и восстановление незавершённой сессии, и такая проверка
 * разъезжалась от одного соседства с другими тестами в той же виртуальной
 * машине. Здесь же вопрос ровно один и данных для него хватает двух
 * аргументов.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi")
class PageRibbonUiTest {

    @get:Rule
    val rule = createComposeRule()

    private fun showRibbon(hasActiveBake: Boolean, phase: GrowthPhase) {
        rule.setContent {
            MadreTheme {
                Surface(color = AppColors.current.paper, modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize()) {
                        PageRibbon(hasActiveBake = hasActiveBake, phase = phase, totalBakes = 3)
                    }
                }
            }
        }
    }

    @Test
    fun `the baking ribbon promises nothing`() {
        showRibbon(hasActiveBake = true, phase = GrowthPhase.PEAK)
        rule.onNodeWithContentDescription("Ляссе: выпечка идёт")
            .assert(hasClickAction().not())
    }

    @Test
    fun `inferred peak ribbon is absent`() {
        showRibbon(hasActiveBake = false, phase = GrowthPhase.PEAK)
        rule.onNodeWithContentDescription("Закваска на пике").assertDoesNotExist()
    }

    @Test
    fun `inferred hunger ribbon is absent`() {
        showRibbon(hasActiveBake = false, phase = GrowthPhase.HUNGRY)
        rule.onNodeWithContentDescription("Закваска давно не кормлена").assertDoesNotExist()
    }

    /**
     * Пока идёт выпечка, mood-ляссе не показывается вовсе — иначе поверх
     * страницы висели бы обе ленты разом.
     */
    @Test
    fun `only one ribbon lies on the page`() {
        showRibbon(hasActiveBake = true, phase = GrowthPhase.HUNGRY)
        rule.onNodeWithContentDescription("Закваска давно не кормлена").assertDoesNotExist()
    }
}
