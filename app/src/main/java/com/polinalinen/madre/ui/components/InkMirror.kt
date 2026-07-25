package com.polinalinen.madre.ui.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.polinalinen.madre.ui.theme.AppColors

/**
 * DESIGN-V4.md Cycle 10, фича «Зеркальный отпечаток» (InkMirror). Свежая
 * рукописная помета на полях не успела высохнуть — при закрытии разворота
 * чернила отпечатались зеркальным следом на соседней странице. След живёт
 * недолго: за пару суток выцветает без следа, как настоящий отпечаток
 * непросохших чернил.
 *
 * Математика выцветания — чистые функции (юнит-тест InkMirrorTest),
 * рендер — [InkMirrorImprint]: тот же текст, отражённый по горизонтали
 * (scaleX = -1) с низкой альфой.
 */
object InkMirror {
    /** Даже только что поставленный отпечаток бледнее самой пометы. */
    const val MAX_ALPHA = 0.26f

    /** За двое суток чернильный след выцветает полностью. */
    const val FADE_MILLIS = 172_800_000L

    /** Бледнее этого след неотличим от бумаги — не рисуем. */
    const val MIN_VISIBLE_ALPHA = 0.02f

    /**
     * Бледность следа по возрасту пометы. Квадратичное затухание: свежий
     * отпечаток заметен, но выцветает быстрее к концу первого дня.
     * Возраст из будущего (рассинхрон часов) считается свежим.
     */
    fun alphaFor(ageMillis: Long): Float {
        val age = ageMillis.coerceAtLeast(0L)
        if (age >= FADE_MILLIS) return 0f
        val left = 1f - age / FADE_MILLIS.toFloat()
        return MAX_ALPHA * left * left
    }

    /** Ещё не высохла — отпечаток видно. */
    fun isWet(ageMillis: Long): Boolean = alphaFor(ageMillis) > MIN_VISIBLE_ALPHA

    /**
     * Какая из помет отпечаталась: самая свежая из непросохших. Отпечаток
     * один — разворот закрывали последним по свежим чернилам именно её.
     * Null — все давно высохли (или помет нет вовсе).
     */
    fun freshestWetIndex(nowMillis: Long, timestamps: List<Long>): Int? =
        timestamps.withIndex()
            .filter { isWet(nowMillis - it.value) }
            .maxByOrNull { it.value }
            ?.index
}

/**
 * Зеркальный след пометы: тот же рукописный текст, отражённый по горизонтали,
 * с альфой от возраста. Высохший (alpha ниже порога) не рисуется вовсе.
 */
@Composable
fun InkMirrorImprint(text: String, ageMillis: Long, modifier: Modifier = Modifier) {
    if (!InkMirror.isWet(ageMillis)) return
    val colors = AppColors.current
    Text(
        text,
        color = colors.espresso.copy(alpha = InkMirror.alphaFor(ageMillis)),
        fontFamily = FontFamily.Cursive,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        modifier = modifier.graphicsLayer {
            // Отражение по горизонтали: чернила легли на соседнюю страницу
            // задом наперёд. Лёгкий обратный наклон — помета писалась с -1°.
            scaleX = -1f
            rotationZ = 1f
        },
    )
}
