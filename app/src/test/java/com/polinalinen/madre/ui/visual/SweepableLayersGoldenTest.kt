package com.polinalinen.madre.ui.visual

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.polinalinen.madre.ui.components.crumbs
import com.polinalinen.madre.ui.components.dustLayer
import com.polinalinen.madre.ui.theme.MadreTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Cycle 16: крошки и пыль — единственные два «живых» слоя книги: у них есть
 * жест (горизонтальный свайп смахивает) и анимация полёта частиц. Именно из-за
 * них эти модификаторы нельзя было свести к drawWithCache, и именно они —
 * самый рискованный кусок перевода с composed {} на Modifier.Node.
 *
 * Ассертом «крошки на месте» не проверишь: слой ничего не пишет в семантику,
 * он только рисует. Поэтому здесь золотые снимки, и снимаются они ПАРАМИ:
 * страница до свайпа и она же после того, как смахнули и полёт закончился.
 *
 * Пара важнее одиночного снимка. Первый снимок ловит раскладку частиц, второй —
 * что жест вообще дошёл до модификатора и что анимация досчиталась до конца.
 * Сломай перевод так, что свайп перестанет ловиться, — первый снимок останется
 * зелёным, а второй покраснеет.
 *
 * Часы теста ручные (autoAdvance = false): полёт длится 850 мс у крошек и
 * 650 мс у пыли, и снимок обязан приходиться на один и тот же момент, иначе
 * эталон будет ловить случайную фазу анимации.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi")
class SweepableLayersGoldenTest {

    @get:Rule
    val rule = createComposeRule()

    private companion object {
        const val PAGE = "page"
        const val SEED = 4242L
    }

    private fun page(content: @androidx.compose.runtime.Composable () -> Modifier) {
        rule.mainClock.autoAdvance = false
        rule.setContent {
            MadreTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().testTag(PAGE).then(content()))
                }
            }
        }
        rule.mainClock.advanceTimeBy(50)
    }

    /** Смахивает слой и доводит полёт до конца — [flightMs] с запасом. */
    private fun sweep(flightMs: Long) {
        rule.onNodeWithTag(PAGE).performTouchInput { swipeRight() }
        rule.mainClock.advanceTimeBy(flightMs + 200)
    }

    @Test
    fun `крошки часто печёного рецепта лежат на странице`() {
        page { Modifier.crumbs(bakeCount = 12, seed = SEED) }
        rule.onRoot().captureRoboImage()
    }

    @Test
    fun `смахнутые крошки уходят со страницы`() {
        page { Modifier.crumbs(bakeCount = 12, seed = SEED) }
        sweep(flightMs = 850)
        rule.onRoot().captureRoboImage()
    }

    @Test
    fun `пыль давно не открытой главы лежит на странице`() {
        page { Modifier.dustLayer(daysSinceOpened = 90, seed = SEED) }
        rule.onRoot().captureRoboImage()
    }

    @Test
    fun `смахнутая пыль уходит со страницы`() {
        page { Modifier.dustLayer(daysSinceOpened = 90, seed = SEED) }
        sweep(flightMs = 650)
        rule.onRoot().captureRoboImage()
    }
}
