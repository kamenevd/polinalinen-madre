package com.polinalinen.madre.ui.components

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polinalinen.madre.ui.theme.AppColors
import kotlin.math.abs
import kotlin.random.Random
import kotlinx.coroutines.launch

/**
 * DESIGN-V4.md Cycle 10, фича «Слипшиеся страницы» (StuckPages). Капля мёда
 * или теста склеила две страницы по правому обрезу. Отлепить можно только
 * как в настоящей книге — медленно провести пальцем вдоль края; резкий рывок
 * почти не помогает (порвёшь бумагу). Под расклеенной кромкой — забытая
 * запись. Редкое случайное событие; расклеенная глава остаётся расклеенной
 * навсегда (SharedPreferences, ключ [prefsKey]).
 *
 * Вероятность, «медленная» математика жеста и хаптика — чистые функции
 * (юнит-тест StuckPagesTest), рисование и жест — [StuckPagesOverlay] ниже.
 */
object StuckPages {
    /** Реже жучка (BookWorm): склейка — событие на редкую каплю. */
    const val CHANCE = 0.07f

    /** Хаптика — тихий щелчок на каждую десятую часть расклейки. */
    const val HAPTIC_STEPS = 10

    /** Потолок засчитанного пути за одно drag-событие: быстрый рывок теряет излишек. */
    const val MAX_STEP_DP = 6f

    fun prefsKey(recipeId: String): String = "stuck_pages_freed_$recipeId"

    /** Бросок кубика на это открытие; расклеенное заново не слипается. */
    fun appears(seed: Long, alreadyFreed: Boolean): Boolean =
        !alreadyFreed && Random(seed).nextFloat() < CHANCE

    /**
     * Засчитанный путь за одно drag-событие. Медленный палец приходит мелкими
     * порциями — считается целиком; быстрый свайп приносит несколько крупных
     * событий, и всё сверх [maxStepPx] пропадает. Направление не важно —
     * край гладят и вверх, и вниз.
     */
    fun peelGain(dragAmountPx: Float, maxStepPx: Float): Float =
        abs(dragAmountPx).coerceAtMost(abs(maxStepPx))

    /** Сколько медленного пути нужно: чуть больше одного прохода вдоль кромки. */
    fun requiredTravelPx(stripHeightPx: Float): Float = stripHeightPx * 1.2f

    /** Сколько десятых границ пройдено между двумя значениями прогресса. */
    fun hapticTicks(progressBefore: Float, progressAfter: Float): Int =
        (progressAfter.coerceIn(0f, 1f) * HAPTIC_STEPS).toInt() -
            (progressBefore.coerceIn(0f, 1f) * HAPTIC_STEPS).toInt()

    /**
     * Сдвиг кромки вправо: пока [peel] растёт, страница лишь слегка отходит,
     * а после расклейки [release] уносит её за край с ускорением.
     */
    fun flapDx(peel: Float, release: Float, flapWidthPx: Float): Float =
        flapWidthPx * (0.25f * peel.coerceIn(0f, 1f) + 1.15f * release * release)

    /** Отпущенная кромка отгибается, как настоящий лист, а не уезжает линейкой. */
    fun flapRotationDeg(release: Float): Float = release * 9f

    /** Капля клея на кромке: [y] — доля высоты, [radius] — доля ширины кромки. */
    data class DropSpec(val y: Float, val radius: Float, val alpha: Float)

    /** Детерминированная капля — не мигает при рекомпозиции. */
    fun dropFor(seed: Long): DropSpec {
        val rng = Random(seed)
        return DropSpec(
            y = 0.22f + rng.nextFloat() * 0.56f,
            radius = 0.30f + rng.nextFloat() * 0.18f,
            alpha = 0.22f + rng.nextFloat() * 0.12f,
        )
    }

    /** Забытые записи под склейкой — что могло прятаться между страниц годами. */
    val HIDDEN_NOTES = listOf(
        "щепотка сахара в опару — и корка запоёт",
        "в дождь тесто просит лишнюю ложку муки",
        "если тесто спешит — уберите его в холод, пусть подумает",
        "печь любит терпеливых и тёплые руки",
        "капнула мёдом — значит, пекли с чаем. хороший был день",
    )

    /** Что нашлось под склейкой; выбор детерминирован от события. */
    fun hiddenNote(seed: Long): String = HIDDEN_NOTES[Random(seed).nextInt(HIDDEN_NOTES.size)]
}

/**
 * Накладывается на viewport разворота (matchParentSize внутри Box). Склейка —
 * узкая кромка второй страницы вдоль правого обреза: вертикальный drag по ней
 * забирает себе кромка (скролл в этой полосе временно жертвуется — страницы же
 * слиплись), остальной экран событий не теряет. Каждая десятая часть пути —
 * тихий haptic-щелчок, расклейка — долгий; кромка отходит и уносится за край,
 * под ней — записка, тап убирает её.
 */
@Composable
fun StuckPagesOverlay(recipeId: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("madre_prefs", Context.MODE_PRIVATE) }
    val alreadyFreed = remember { prefs.getBoolean(StuckPages.prefsKey(recipeId), false) }
    // Seed — момент открытия страницы: бросок кубика один на визит (как BookWorm).
    val seed = remember { System.currentTimeMillis() }
    val appears = remember(seed) { StuckPages.appears(seed, alreadyFreed) }
    if (!appears) return

    val colors = AppColors.current
    val haptics = LocalHapticFeedback.current
    val drop = remember { StuckPages.dropFor(seed) }
    var travelPx by remember { mutableFloatStateOf(0f) }
    var freed by remember { mutableStateOf(false) }
    var noteDismissed by remember { mutableStateOf(false) }
    val release = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    Box(modifier) {
        if (release.value < 1f) {
            BoxWithConstraints(
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .width(52.dp),
            ) {
                val density = LocalDensity.current
                val heightPx = with(density) { maxHeight.toPx() }
                val widthPx = with(density) { maxWidth.toPx() }
                val maxStepPx = with(density) { StuckPages.MAX_STEP_DP.dp.toPx() }
                val required = StuckPages.requiredTravelPx(heightPx)
                val progress = (travelPx / required).coerceIn(0f, 1f)

                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = StuckPages.flapDx(progress, release.value, widthPx)
                            rotationZ = StuckPages.flapRotationDeg(release.value)
                            alpha = 1f - release.value * 0.35f
                        }
                        .pointerInput(Unit) {
                            detectVerticalDragGestures { _, amount ->
                                if (freed) return@detectVerticalDragGestures
                                val before = (travelPx / required).coerceIn(0f, 1f)
                                travelPx += StuckPages.peelGain(amount, maxStepPx)
                                val after = (travelPx / required).coerceIn(0f, 1f)
                                if (StuckPages.hapticTicks(before, after) > 0) {
                                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                                if (after >= 1f) {
                                    freed = true
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    prefs.edit()
                                        .putBoolean(StuckPages.prefsKey(recipeId), true)
                                        .apply()
                                    scope.launch {
                                        release.animateTo(1f, tween(700, easing = FastOutLinearInEasing))
                                    }
                                }
                            }
                        }
                        .drawBehind {
                            // Кромка второй страницы: чуть темнее бумаги, со швом слева.
                            drawRect(colors.parchment.copy(alpha = 0.96f))
                            drawLine(
                                color = colors.cocoa.copy(alpha = 0.25f),
                                start = Offset(0f, 0f),
                                end = Offset(0f, size.height),
                                strokeWidth = 1.dp.toPx(),
                            )
                            // Шов склейки: короткие стежки клея вдоль края; сверху,
                            // где уже отлепили (доля progress), их больше нет.
                            val stitchStep = 22.dp.toPx()
                            var y = stitchStep / 2
                            while (y < size.height) {
                                if (y > progress * size.height) {
                                    drawLine(
                                        color = colors.amberDeep.copy(alpha = 0.30f),
                                        start = Offset(0f, y),
                                        end = Offset(5.dp.toPx(), y - 3.dp.toPx()),
                                        strokeWidth = 1.dp.toPx(),
                                    )
                                }
                                y += stitchStep
                            }
                            // Сама капля — виновница склейки, тает по мере расклейки.
                            drawCircle(
                                color = colors.amberDeep.copy(alpha = drop.alpha * (1f - progress * 0.7f)),
                                radius = drop.radius * size.width,
                                center = Offset(2.dp.toPx(), drop.y * size.height),
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "страницы слиплись — проведите медленно по краю",
                        color = colors.cocoa.copy(alpha = 0.55f),
                        fontFamily = FontFamily.SansSerif,
                        fontSize = 8.sp,
                        letterSpacing = 1.5.sp,
                        maxLines = 1,
                        softWrap = false,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .requiredWidth(320.dp)
                            .rotate(-90f),
                    )
                }
            }
        }

        if (freed && release.value >= 1f && !noteDismissed) {
            // Находка под склейкой: старая запись на выпавшем листке.
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 20.dp)
                    .rotate(-2f)
                    .drawBehind { drawRect(colors.cream) }
                    .clickable { noteDismissed = true }
                    .padding(horizontal = 16.dp, vertical = 12.dp)
                    .requiredWidth(190.dp),
            ) {
                Text(
                    "под слипшимися страницами",
                    color = colors.cocoa,
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 8.sp,
                    letterSpacing = 2.sp,
                )
                Text(
                    StuckPages.hiddenNote(seed),
                    color = colors.espresso.copy(alpha = 0.8f),
                    fontFamily = FontFamily.Cursive,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}
