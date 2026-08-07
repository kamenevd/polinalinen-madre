package com.polinalinen.madre.ui.visual

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.polinalinen.madre.ui.components.coffeeRings
import com.polinalinen.madre.ui.components.dampPaper
import com.polinalinen.madre.ui.components.wornPage
import com.polinalinen.madre.ui.theme.MadreTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Cycle 16: слои бумаги — износ, кофейные круги и отсыревшие кромки — переехали
 * с composed {} на drawWithCache. Перевод обязан быть чисто механическим: ни
 * один пиксель страницы не должен поехать.
 *
 * Проверить это ассертом нельзя — «след, а не пятно» в числах не записывается.
 * Поэтому здесь золотые снимки: эталоны сняты СТАРЫМ кодом (до перевода), а
 * verifyRoborazziDebug сверяет с ними новый. Разошлось хоть на пиксель — значит
 * перевод не механический, и это надо увидеть, а не узнать от Полины.
 *
 * Все три слоя детерминированы от seed, поэтому снимок воспроизводим: случайных
 * чисел, меняющихся от запуска к запуску, здесь нет ни одного.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h640dp-xhdpi")
class PaperTextureGoldenTest {

    @Test
    fun `износ страницы часто печёного рецепта`() {
        captureRoboImage {
            MadreTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // 20 выпечек — выше всех трёх порогов WornPage разом:
                    // и потемнение края, и отпечаток пальца, и потёртость корешка.
                    Box(Modifier.fillMaxSize().wornPage(bakeCount = 20, seed = 4242L))
                }
            }
        }
    }

    @Test
    fun `кофейные круги от трёх прерванных выпечек`() {
        captureRoboImage {
            MadreTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().coffeeRings(cancelledCount = 3, seed = 4242L))
                }
            }
        }
    }

    @Test
    fun `отсыревшая бумага в дождь`() {
        captureRoboImage {
            MadreTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().dampPaper(alpha = 0.8f, seed = 4242L))
                }
            }
        }
    }

    /** Все три слоя разом, в том же порядке, в каком их вешает разворот рецепта. */
    @Test
    fun `три слоя бумаги вместе`() {
        captureRoboImage {
            MadreTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .dampPaper(alpha = 0.6f, seed = 7L)
                            .coffeeRings(cancelledCount = 2, seed = 4242L)
                            .wornPage(bakeCount = 12, seed = 4242L)
                    )
                }
            }
        }
    }
}
