package com.polinalinen.madre.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.polinalinen.madre.ui.theme.AppColors
import java.io.File

/**
 * DESIGN-V4.md Cycle 6, фича «Старое фото» (AgedPhoto): вклеенная фотокарточка
 * стареет вместе с книгой. Свежая — цветная, через неделю выцветает в сепию,
 * через месяц — блёкнет, как карточка из старого альбома. Возраст → фильтр —
 * чистые функции, вынесены из Composable ради юнит-теста (тот же приём, что
 * InkBlot/WornPage/LightPage).
 */
object PhotoAging {
    const val WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000
    const val MONTH_MILLIS = 30L * 24 * 60 * 60 * 1000

    enum class Stage { FRESH, SEPIA, FADED }

    fun stageFor(ageMillis: Long): Stage = when {
        ageMillis < WEEK_MILLIS -> Stage.FRESH
        ageMillis < MONTH_MILLIS -> Stage.SEPIA
        else -> Stage.FADED
    }

    fun colorMatrix(ageMillis: Long): ColorMatrix = when (stageFor(ageMillis)) {
        Stage.FRESH -> ColorMatrix()
        Stage.SEPIA -> sepia()
        Stage.FADED -> faded()
    }

    /**
     * Сепия: обесцветить, затем прогреть каналы в тона бумаги книги —
     * красный чуть поднят, синий заметно придавлен.
     */
    private fun sepia(): ColorMatrix {
        val warm = ColorMatrix(
            floatArrayOf(
                1.10f, 0f, 0f, 0f, 8f,
                0f, 0.97f, 0f, 0f, 3f,
                0f, 0f, 0.78f, 0f, -10f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        warm *= ColorMatrix().apply { setToSaturation(0f) }
        return warm
    }

    /** Выцветшее: та же сепия, но с упавшим контрастом и общей засветкой. */
    private fun faded(): ColorMatrix {
        val wash = ColorMatrix(
            floatArrayOf(
                0.80f, 0f, 0f, 0f, 45f,
                0f, 0.80f, 0f, 0f, 45f,
                0f, 0f, 0.80f, 0f, 45f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        wash *= sepia()
        return wash
    }
}

/**
 * Бумажные уголки-держатели фотокарточки, как в семейном альбоме — общий
 * рисунок для FeedingFormScreen (пустой слот) и AgedPhoto (вклеенное фото).
 */
fun DrawScope.drawPhotoHolders(color: Color, cornerPx: Float) {
    fun holder(x: Float, y: Float, dx: Float, dy: Float) {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + dx * cornerPx, y)
            lineTo(x, y + dy * cornerPx)
            close()
        }
        drawPath(path, color)
    }
    holder(0f, 0f, 1f, 1f)
    holder(size.width, 0f, -1f, 1f)
    holder(0f, size.height, 1f, -1f)
    holder(size.width, size.height, -1f, -1f)
}

/**
 * Вклеенная фотокарточка выпечки: cream-рамка, лёгкий поворот, уголки-держатели
 * поверх — и ColorMatrix-старение от возраста записи ([takenAtMillis]).
 */
@Composable
fun AgedPhoto(
    photoPath: String,
    takenAtMillis: Long,
    modifier: Modifier = Modifier,
    height: Dp = 170.dp,
    rotationDeg: Float = -1f,
    nowMillis: Long = System.currentTimeMillis(),
) {
    val colors = AppColors.current
    val matrix = remember(photoPath, takenAtMillis) { PhotoAging.colorMatrix(nowMillis - takenAtMillis) }
    Box(
        modifier
            .fillMaxWidth()
            .rotate(rotationDeg)
            .drawBehind { drawRect(colors.cream) }
            .padding(10.dp)
    ) {
        Box(
            Modifier.drawWithContent {
                drawContent()
                drawPhotoHolders(colors.cocoa.copy(alpha = 0.55f), 14.dp.toPx())
            }
        ) {
            AsyncImage(
                model = File(photoPath),
                contentDescription = "Вклеенная фотокарточка выпечки",
                contentScale = ContentScale.Crop,
                colorFilter = ColorFilter.colorMatrix(matrix),
                modifier = Modifier.fillMaxWidth().height(height),
            )
        }
    }
}
