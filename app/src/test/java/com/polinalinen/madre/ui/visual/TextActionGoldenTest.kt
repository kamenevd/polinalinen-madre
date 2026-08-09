package com.polinalinen.madre.ui.visual

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.polinalinen.madre.ui.components.TextAction
import com.polinalinen.madre.ui.theme.AppColors
import com.polinalinen.madre.ui.theme.MadreTheme
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Cycle 18: тихое действие обязано отличаться от подписи рядом — а «отличается
 * ли оно» не проверяется ни семантикой, ни размером мишени. Пунктир под словами
 * живёт только в пикселях, поэтому и сверяется по пикселям.
 *
 * Рядом с действием намеренно стоит обычная строка-подпись того же цвета: снимок
 * ловит не «TextAction нарисовался», а «его видно как действие среди текста».
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "ru-w360dp-h200dp-xhdpi")
class TextActionGoldenTest {

    @Test
    fun `тихое действие отличается от подписи рядом`() {
        captureRoboImage {
            MadreTheme {
                Surface(color = AppColors.current.paper, modifier = Modifier.fillMaxSize()) {
                    Column(
                        Modifier.padding(horizontal = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            "общая книга сейчас не отвечает",
                            color = AppColors.current.cocoa,
                            fontFamily = FontFamily.Serif,
                            fontStyle = FontStyle.Italic,
                            fontSize = 15.sp,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                        TextAction(label = "заглянуть ещё раз", onClick = {})
                        TextAction(label = "заглянуть ещё раз", onClick = {}, enabled = false)
                    }
                }
            }
        }
    }
}
