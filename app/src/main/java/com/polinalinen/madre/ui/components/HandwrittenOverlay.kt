package com.polinalinen.madre.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polinalinen.madre.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * DESIGN-V4.md Cycle 4, фича «Правка от руки» (HandwrittenEdit). Пользователь
 * рисует рукописные правки прямо поверх книжного текста рецепта — как пометки
 * пером в настоящей кулинарной книге. Правки сохраняются как bitmap в internal
 * storage, файл привязан к recipeId. Имя файла — чистая функция под юнит-тестом.
 */
object HandwrittenEdit {
    const val INK_ALPHA = 0.85f
    const val STROKE_WIDTH_DP = 2.5f

    /** Имя файла правок: только буквы/цифры recipeId, остальное — «_». */
    fun fileNameFor(recipeId: String): String =
        "handwritten_" + recipeId.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("") + ".png"

    suspend fun save(context: Context, recipeId: String, bitmap: Bitmap) = withContext(Dispatchers.IO) {
        context.filesDir.resolve(fileNameFor(recipeId)).outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
    }

    suspend fun load(context: Context, recipeId: String): Bitmap? = withContext(Dispatchers.IO) {
        val file = context.filesDir.resolve(fileNameFor(recipeId))
        if (file.exists()) BitmapFactory.decodeFile(file.path) else null
    }

    /**
     * Прошлые правки + новые штрихи -> один bitmap размером с область текста.
     * Прошлый слой растягивается на текущий размер: текст мог перетечь, но
     * правки остаются примерно у своих строк.
     */
    fun render(
        previous: Bitmap?,
        strokes: List<List<Offset>>,
        size: IntSize,
        strokeWidthPx: Float,
        inkArgb: Int,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(size.width, size.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bitmap)
        if (previous != null) {
            canvas.drawBitmap(previous, null, Rect(0, 0, size.width, size.height), null)
        }
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = inkArgb
            alpha = (INK_ALPHA * 255).toInt()
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = strokeWidthPx
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
        }
        strokes.forEach { points ->
            if (points.isEmpty()) return@forEach
            if (points.size == 1) {
                canvas.drawPoint(points[0].x, points[0].y, paint)
            } else {
                val path = android.graphics.Path().apply {
                    moveTo(points[0].x, points[0].y)
                    points.drop(1).forEach { lineTo(it.x, it.y) }
                }
                canvas.drawPath(path, paint)
            }
        }
        return bitmap
    }
}

/**
 * Оборачивает текст рецепта слоем рукописных правок: кнопка-переключатель
 * режима рисования + overlay-канва поверх [content]. Пока режим включён,
 * палец рисует чернилами Espresso (и скролл по этой области стоит — как и
 * положено, когда пишешь в книге, придерживая страницу).
 */
@Composable
fun HandwrittenEditSurface(
    recipeId: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = AppColors.current
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    var editing by remember { mutableStateOf(false) }
    val strokes = remember { mutableStateListOf<List<Offset>>() }
    var currentStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var saved by remember { mutableStateOf<ImageBitmap?>(null) }
    var savedAndroidBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var overlaySize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(recipeId) {
        savedAndroidBitmap = HandwrittenEdit.load(context, recipeId)
        saved = savedAndroidBitmap?.asImageBitmap()
    }

    fun finishEditing() {
        val newStrokes = strokes.toList() + listOf(currentStroke).filter { it.isNotEmpty() }
        if (newStrokes.isNotEmpty() && overlaySize.width > 0 && overlaySize.height > 0) {
            val bitmap = HandwrittenEdit.render(
                previous = savedAndroidBitmap,
                strokes = newStrokes,
                size = overlaySize,
                strokeWidthPx = with(density) { HandwrittenEdit.STROKE_WIDTH_DP.dp.toPx() },
                inkArgb = colors.espresso.toArgb(),
            )
            savedAndroidBitmap = bitmap
            saved = bitmap.asImageBitmap()
            strokes.clear()
            currentStroke = emptyList()
            scope.launch { HandwrittenEdit.save(context, recipeId, bitmap) }
        }
        editing = false
    }

    Column(modifier) {
        Text(
            if (editing) "готово — вписать в книгу" else "редактировать от руки",
            color = if (editing) colors.terracotta else colors.crust,
            fontFamily = FontFamily.SansSerif,
            fontSize = 10.sp,
            letterSpacing = 2.sp,
            modifier = Modifier
                .padding(horizontal = 22.dp, vertical = 6.dp)
                .clickable { if (editing) finishEditing() else editing = true },
        )
        Box(Modifier.fillMaxWidth().onSizeChanged { overlaySize = it }) {
            content()

            // Слой сохранённых правок — всегда виден, как чернила в книге.
            saved?.let { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = "Рукописные правки",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.matchParentSize(),
                )
            }

            // Живые, ещё не сохранённые штрихи текущего сеанса рисования.
            if (strokes.isNotEmpty() || currentStroke.isNotEmpty()) {
                Canvas(Modifier.matchParentSize()) {
                    val ink = colors.espresso.copy(alpha = HandwrittenEdit.INK_ALPHA)
                    val width = HandwrittenEdit.STROKE_WIDTH_DP.dp.toPx()
                    (strokes + listOf(currentStroke)).forEach { points ->
                        if (points.size < 2) {
                            points.firstOrNull()?.let { drawCircle(ink, radius = width / 2, center = it) }
                        } else {
                            val path = Path().apply {
                                moveTo(points[0].x, points[0].y)
                                points.drop(1).forEach { lineTo(it.x, it.y) }
                            }
                            drawPath(path, ink, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
                        }
                    }
                }
            }

            if (editing) {
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(recipeId) {
                            detectDragGestures(
                                onDragStart = { offset -> currentStroke = listOf(offset) },
                                onDrag = { change, _ -> currentStroke = currentStroke + change.position },
                                onDragEnd = {
                                    if (currentStroke.isNotEmpty()) strokes.add(currentStroke)
                                    currentStroke = emptyList()
                                },
                                onDragCancel = { currentStroke = emptyList() },
                            )
                        }
                )
            }
        }
    }
}
