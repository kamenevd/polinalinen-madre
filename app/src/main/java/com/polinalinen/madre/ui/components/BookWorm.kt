package com.polinalinen.madre.ui.components

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polinalinen.madre.ui.theme.AppColors
import com.polinalinen.madre.ui.theme.Cocoa
import com.polinalinen.madre.ui.theme.Espresso
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * DESIGN-V4.md Cycle 8, фича «Книжный жучок» (BookWorm). Редкое событие:
 * крошечный жучок-книгоед пробегает по левому полю разворота сверху вниз и
 * исчезает. Шанс встречи растёт на давно не открытых главах — жучки любят
 * старые страницы (тот же daysSinceOpened, что у DustLayer). Успеть тапнуть —
 * пасхалка: жучок пойман, счётчик в SharedPreferences (bookworm_caught).
 *
 * Вероятность, траектория и подписи — чистые функции (юнит-тест BookWormTest),
 * анимация — BookWormOverlay ниже.
 */
object BookWorm {
    /** База — примерно раз в 12 открытий. */
    const val BASE_CHANCE = 0.08f
    const val MAX_CHANCE = 0.35f

    /** Пробег занимает секунды — событие, которое легко проморгать. */
    const val RUN_MILLIS = 7000
    const val START_DELAY_MILLIS = 1500L

    /** Шанс встречи: редкий на свежих страницах, чаще на залежавшихся. */
    fun chance(daysSinceOpened: Int): Float =
        (BASE_CHANCE + 0.005f * daysSinceOpened.coerceAtLeast(0)).coerceAtMost(MAX_CHANCE)

    /** Бросок кубика на это открытие; [seed] — момент открытия страницы. */
    fun appears(seed: Long, daysSinceOpened: Int): Boolean =
        Random(seed).nextFloat() < chance(daysSinceOpened)

    /**
     * Бег по левому полю: базовая колея у корешка плюс синусоидальное виляние —
     * у каждого жучка свои размах и фаза (от [seed]). Возвращает долю ширины.
     */
    fun xFraction(progress: Float, seed: Long): Float {
        val rng = Random(seed)
        val waves = 4 + rng.nextInt(4)
        val phase = rng.nextFloat() * 2f * PI.toFloat()
        val amplitude = 0.015f + rng.nextFloat() * 0.02f
        return 0.06f + amplitude * sin(progress * waves * 2f * PI.toFloat() + phase)
    }

    /** Сверху вниз, не задевая шапку и подвал страницы. Доля высоты. */
    fun yFraction(progress: Float): Float = 0.08f + progress.coerceIn(0f, 1f) * 0.84f

    /** Пасхалка при поимке; [caughtTotal] — сколько всего поймано, включая этого. */
    fun caughtCaption(caughtTotal: Int): String =
        if (caughtTotal <= 1) "поймали книжного жучка"
        else "книжный жучок пойман — уже $caughtTotal"
}

/**
 * Накладывается на viewport разворота (matchParentSize внутри Box): жучок
 * бежит по «стеклу» страницы, не уезжая со скроллом. Тап-мишень — маленький
 * Box, путешествующий вместе с жучком: остальная страница событий не теряет
 * (никакого полноэкранного перехвата касаний). Не поймали — убежал и до
 * следующего удачного броска не показывается.
 */
@Composable
fun BookWormOverlay(daysSinceOpened: Int, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("madre_prefs", Context.MODE_PRIVATE) }
    // Seed — момент открытия страницы: бросок кубика один на визит.
    val seed = remember { System.currentTimeMillis() }
    val appears = remember(seed) { BookWorm.appears(seed, daysSinceOpened) }
    if (!appears) return

    val colors = AppColors.current
    val progress = remember { Animatable(0f) }
    var caught by remember { mutableStateOf(false) }
    var escaped by remember { mutableStateOf(false) }
    var caughtTotal by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        delay(BookWorm.START_DELAY_MILLIS)
        progress.animateTo(1f, tween(BookWorm.RUN_MILLIS, easing = LinearEasing))
        if (!caught) escaped = true
    }

    if (escaped) return

    BoxWithConstraints(modifier) {
        val density = LocalDensity.current
        val widthPx = with(density) { maxWidth.toPx() }
        val heightPx = with(density) { maxHeight.toPx() }
        val hitSizeDp = 28.dp
        val hitPx = with(density) { hitSizeDp.toPx() }
        val x = BookWorm.xFraction(progress.value, seed) * widthPx - hitPx / 2
        val y = BookWorm.yFraction(progress.value) * heightPx - hitPx / 2

        if (caught) {
            Text(
                BookWorm.caughtCaption(caughtTotal),
                color = colors.cocoa,
                fontFamily = FontFamily.Cursive,
                fontSize = 14.sp,
                modifier = Modifier
                    .offset { IntOffset(x.roundToInt() + 8, y.roundToInt()) }
                    .rotate(-2f),
            )
        } else {
            Canvas(
                Modifier
                    .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                    .size(hitSizeDp)
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            // Одно касание — пойман: событие короткое, ждать
                            // полного tap-цикла жучку не обязательно.
                            awaitPointerEvent()
                            caught = true
                            caughtTotal = prefs.getInt("bookworm_caught", 0) + 1
                            prefs.edit().putInt("bookworm_caught", caughtTotal).apply()
                        }
                    },
            ) {
                drawWorm(progressValue = progress.value)
            }
        }
    }
}

/**
 * Сам жучок — три сегмента тельца друг за другом по ходу бега (вниз), усики
 * и лапки-чёрточки. Перебирает лапками: их наклон качается от progress.
 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawWorm(progressValue: Float) {
    val cx = size.width / 2
    val cy = size.height / 2
    val step = 3.dp.toPx()
    val legSwing = sin(progressValue * 60f) * 1.5.dp.toPx()

    // Тельце: голова ниже по ходу движения, сегменты убывают назад.
    listOf(1.6f, 1.9f, 1.4f).forEachIndexed { i, r ->
        drawCircle(
            color = if (i == 0) Espresso else Cocoa,
            radius = r.dp.toPx(),
            center = Offset(cx, cy + step - i * step),
        )
    }
    // Лапки — по три с каждой стороны, качаются в противофазе.
    repeat(3) { i ->
        val ly = cy + step - i * step
        val swing = if (i % 2 == 0) legSwing else -legSwing
        drawLine(
            Espresso,
            start = Offset(cx - 2.dp.toPx(), ly),
            end = Offset(cx - 4.dp.toPx() - swing, ly + 1.dp.toPx()),
            strokeWidth = 0.8.dp.toPx(),
        )
        drawLine(
            Espresso,
            start = Offset(cx + 2.dp.toPx(), ly),
            end = Offset(cx + 4.dp.toPx() + swing, ly + 1.dp.toPx()),
            strokeWidth = 0.8.dp.toPx(),
        )
    }
    // Усики вперёд-вниз, по ходу бега.
    drawLine(
        Espresso,
        start = Offset(cx, cy + step),
        end = Offset(cx - 2.dp.toPx(), cy + step + 3.dp.toPx()),
        strokeWidth = 0.6.dp.toPx(),
    )
    drawLine(
        Espresso,
        start = Offset(cx, cy + step),
        end = Offset(cx + 2.dp.toPx(), cy + step + 3.dp.toPx()),
        strokeWidth = 0.6.dp.toPx(),
    )
}
