package com.polinalinen.madre.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polinalinen.madre.ui.theme.AppColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * DESIGN-V4.md Cycle 4, фича «Правка от руки» (HandwrittenEdit). Пользователь
 * пишет рукописные правки прямо поверх книжного текста рецепта — как пометки
 * пером в настоящей кулинарной книге.
 *
 * Cycle 11 переписал хранение с растра на ВЕКТОР. Раньше каждый сеанс
 * рисования запекался в один PNG: отменить последний штрих было нечем, а
 * «шаг назад» потребовал бы держать копию bitmap на каждое действие. Теперь
 * правки — это список штрихов, а штрих — список точек в НОРМАЛИЗОВАННЫХ
 * координатах (0..1 от области текста). Отсюда сразу три следствия:
 *
 *  - Undo/Redo работают по одному завершённому штриху и стоят один список;
 *  - правки не плывут, когда текст перетёк и область сменила высоту;
 *  - в памяти нет ни одного лишнего bitmap.
 *
 * Ранее записанные PNG никуда не деваются: они читаются как базовый слой
 * совместимости и лежат под векторными штрихами. Дорисовывать в них нечего —
 * всё новое пишется в вектор.
 */
object HandwrittenEdit {
    const val INK_ALPHA = 0.85f
    const val STROKE_WIDTH_DP = 2.5f

    /** Знаков после запятой в сохранённой координате: 4 — это доли пикселя. */
    private const val COORD_SCALE = 10_000f

    /** Имя файла правок: только буквы/цифры recipeId, остальное — «_». */
    fun fileNameFor(recipeId: String): String = "handwritten_${sanitize(recipeId)}.png"

    /** Имя файла векторной истории — рядом с легаси-растром, но своё. */
    fun strokesFileNameFor(recipeId: String): String = "handwritten_${sanitize(recipeId)}.strokes"

    private fun sanitize(recipeId: String): String =
        recipeId.map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")

    /**
     * История штрихов: что уже написано и что отменено. Неизменяемая — каждое
     * действие возвращает новое состояние, поэтому «шаг назад» стоит ссылку на
     * список, а не копию картинки.
     */
    data class StrokeHistory(
        val strokes: List<List<Offset>> = emptyList(),
        val undone: List<List<Offset>> = emptyList(),
    ) {
        val canUndo: Boolean get() = strokes.isNotEmpty()
        val canRedo: Boolean get() = undone.isNotEmpty()

        /** Новый штрих обрывает ветку отменённого — вернуть его уже нельзя. */
        fun add(stroke: List<Offset>): StrokeHistory =
            if (stroke.isEmpty()) this else StrokeHistory(strokes + listOf(stroke), emptyList())

        /** Снимает ровно последний завершённый штрих; можно дойти до пустой страницы. */
        fun undo(): StrokeHistory =
            if (!canUndo) this else StrokeHistory(strokes.dropLast(1), undone + listOf(strokes.last()))

        /** Возвращает последний отменённый штрих. */
        fun redo(): StrokeHistory =
            if (!canRedo) this else StrokeHistory(strokes + listOf(undone.last()), undone.dropLast(1))
    }

    /**
     * Штрихи в текст: строка на штрих, точки через «;», координаты через «,».
     * Свой формат, а не JSON — чтобы разбор оставался чистой функцией без
     * зависимостей и целиком лежал под юнит-тестом.
     */
    fun encode(strokes: List<List<Offset>>): String =
        strokes.filter { it.isNotEmpty() }.joinToString("\n") { stroke ->
            stroke.joinToString(";") { point ->
                "${Math.round(point.x * COORD_SCALE)},${Math.round(point.y * COORD_SCALE)}"
            }
        }

    /** Разбор [encode]. Битые строки и точки молча пропускаются — правки не крешат книгу. */
    fun decode(raw: String): List<List<Offset>> =
        raw.lineSequence()
            .mapNotNull { line ->
                val points = line.split(';').mapNotNull { chunk ->
                    val parts = chunk.split(',')
                    if (parts.size != 2) return@mapNotNull null
                    val x = parts[0].toFloatOrNull() ?: return@mapNotNull null
                    val y = parts[1].toFloatOrNull() ?: return@mapNotNull null
                    Offset(x / COORD_SCALE, y / COORD_SCALE)
                }
                points.ifEmpty { null }
            }
            .toList()

    suspend fun saveStrokes(context: Context, recipeId: String, strokes: List<List<Offset>>) =
        withContext(Dispatchers.IO) {
            val file = context.filesDir.resolve(strokesFileNameFor(recipeId))
            if (strokes.isEmpty()) file.delete() else file.writeText(encode(strokes))
            Unit
        }

    suspend fun loadStrokes(context: Context, recipeId: String): List<List<Offset>> =
        withContext(Dispatchers.IO) {
            val file = context.filesDir.resolve(strokesFileNameFor(recipeId))
            if (file.exists()) runCatching { decode(file.readText()) }.getOrDefault(emptyList()) else emptyList()
        }

    /**
     * Базовый слой совместимости: правки, записанные растром до Cycle 11.
     * Только чтение — новые штрихи в него не дописываются.
     */
    suspend fun loadLegacyLayer(context: Context, recipeId: String): Bitmap? = withContext(Dispatchers.IO) {
        val file = context.filesDir.resolve(fileNameFor(recipeId))
        if (file.exists()) BitmapFactory.decodeFile(file.path) else null
    }
}

/**
 * Оборачивает текст рецепта слоем рукописных правок: переключатель режима
 * рисования, кнопки «шаг назад»/«шаг вперёд» и overlay-канва поверх [content].
 * Пока режим включён, палец рисует чернилами Espresso (и скролл по этой
 * области стоит — как и положено, когда пишешь в книге, придерживая страницу).
 */
@Composable
fun HandwrittenEditSurface(
    recipeId: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val colors = AppColors.current
    val context = androidx.compose.ui.platform.LocalContext.current

    // Cycle 16: rememberSaveable, а не remember. Рецепт переехал на LazyColumn,
    // и блок «рецепт целиком» теперь выкидывается из композиции, когда уезжает
    // за край экрана. С обычным remember режим правки молча выключался сам,
    // стоило прокрутить страницу вверх и вернуться. Штрихи это переживали
    // (они пишутся на диск на каждое изменение), а вот включённый карандаш — нет.
    var editing by rememberSaveable(recipeId) { mutableStateOf(false) }
    var history by remember(recipeId) { mutableStateOf(HandwrittenEdit.StrokeHistory()) }
    var currentStroke by remember { mutableStateOf<List<Offset>>(emptyList()) }
    var legacyLayer by remember(recipeId) { mutableStateOf<ImageBitmap?>(null) }
    // Пока история не поднята с диска, писать на диск нельзя — иначе пустое
    // начальное состояние затрёт настоящие правки при возврате на экран.
    var loaded by remember(recipeId) { mutableStateOf(false) }

    LaunchedEffect(recipeId) {
        legacyLayer = HandwrittenEdit.loadLegacyLayer(context, recipeId)?.asImageBitmap()
        history = HandwrittenEdit.StrokeHistory(HandwrittenEdit.loadStrokes(context, recipeId))
        loaded = true
    }

    // Сохраняем на каждое изменение истории, а не только по «готово»: уйти со
    // страницы посреди правки — обычное дело, и написанное должно пережить это.
    LaunchedEffect(history, loaded) {
        if (loaded) HandwrittenEdit.saveStrokes(context, recipeId, history.strokes)
    }

    Column(modifier) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (editing) "готово — вписать в книгу" else "редактировать от руки",
                color = if (editing) colors.terracotta else colors.crust,
                fontFamily = FontFamily.SansSerif,
                fontSize = 10.sp,
                letterSpacing = 2.sp,
                modifier = Modifier
                    .weight(1f)
                    .clickable {
                        if (editing) currentStroke = emptyList()
                        editing = !editing
                    },
            )
            if (editing) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    InkHistoryButton(
                        icon = Icons.Filled.Undo,
                        description = "Убрать последний штрих",
                        enabled = history.canUndo,
                        onClick = { history = history.undo() },
                    )
                    InkHistoryButton(
                        icon = Icons.Filled.Redo,
                        description = "Вернуть убранный штрих",
                        enabled = history.canRedo,
                        onClick = { history = history.redo() },
                    )
                }
            }
        }

        Box(Modifier.fillMaxWidth()) {
            content()

            // Базовый слой — правки, записанные растром до Cycle 11.
            legacyLayer?.let { bitmap ->
                Image(
                    bitmap = bitmap,
                    contentDescription = "Прежние рукописные правки",
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.matchParentSize(),
                )
            }

            // Векторные штрихи: и сохранённые, и тот, что палец ведёт прямо сейчас.
            Canvas(Modifier.matchParentSize()) {
                val ink = colors.espresso.copy(alpha = HandwrittenEdit.INK_ALPHA)
                val width = HandwrittenEdit.STROKE_WIDTH_DP.dp.toPx()
                (history.strokes + listOf(currentStroke)).forEach { stroke ->
                    if (stroke.isEmpty()) return@forEach
                    val points = stroke.map { Offset(it.x * size.width, it.y * size.height) }
                    if (points.size < 2) {
                        drawCircle(ink, radius = width / 2, center = points[0])
                    } else {
                        val path = Path().apply {
                            moveTo(points[0].x, points[0].y)
                            points.drop(1).forEach { lineTo(it.x, it.y) }
                        }
                        drawPath(path, ink, style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    }
                }
            }

            if (editing) {
                Box(
                    Modifier
                        .matchParentSize()
                        .pointerInput(recipeId) {
                            val area = Size(size.width.toFloat(), size.height.toFloat())
                            fun normalize(offset: Offset) = Offset(
                                (offset.x / area.width).coerceIn(0f, 1f),
                                (offset.y / area.height).coerceIn(0f, 1f),
                            )
                            detectDragGestures(
                                onDragStart = { offset -> currentStroke = listOf(normalize(offset)) },
                                onDrag = { change, _ -> currentStroke = currentStroke + normalize(change.position) },
                                onDragEnd = {
                                    history = history.add(currentStroke)
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

/** Кнопка истории: доступная (contentDescription) и гаснущая, когда идти некуда. */
@Composable
private fun InkHistoryButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = AppColors.current
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = colors.espresso,
            disabledContentColor = colors.flour,
        ),
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(20.dp))
    }
}
