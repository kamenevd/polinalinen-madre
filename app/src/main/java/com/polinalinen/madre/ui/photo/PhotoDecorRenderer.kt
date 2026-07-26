package com.polinalinen.madre.ui.photo

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min

/**
 * Рисование выбранного [PhotoDecor] поверх снимка — один код и для превью в
 * редакторе, и для итогового файла. Это сознательно: превью не «похоже» на
 * результат, оно и есть результат, только в меньшем разрешении.
 *
 * Всё локально — android.graphics.Canvas, никакой сети и никакой генерации.
 */
object PhotoDecorRenderer {

    /** Ширина паспарту — доля от короткой стороны снимка. */
    private const val MAT_FRACTION = 0.055f

    /** Радиус штампа — доля от короткой стороны кадра вместе с рамкой. */
    private const val STAMP_FRACTION = 0.11f

    /** Цвета «Warm Paper», переданные из темы: рисовать здесь Compose-цвета нечем. */
    data class Palette(val paper: Int, val cream: Int, val ink: Int, val accent: Int)

    /**
     * Собирает итоговый кадр: бумажная подложка → снимок (при желании прогретый)
     * → рамка → штамп. Исходный [source] не трогается и остаётся за вызывающим.
     */
    fun render(source: Bitmap, decor: PhotoDecor, palette: Palette): Bitmap {
        val pad = if (decor.frame == PhotoFrame.NONE) 0 else {
            (min(source.width, source.height) * MAT_FRACTION).toInt().coerceAtLeast(1)
        }
        val out = Bitmap.createBitmap(source.width + pad * 2, source.height + pad * 2, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(if (pad > 0) palette.cream else palette.paper)

        val photo = RectF(
            pad.toFloat(),
            pad.toFloat(),
            (pad + source.width).toFloat(),
            (pad + source.height).toFloat(),
        )
        val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            if (decor.warm) colorFilter = ColorMatrixColorFilter(warmMatrix())
        }
        canvas.drawBitmap(source, Rect(0, 0, source.width, source.height), photo, photoPaint)

        drawFrame(canvas, decor.frame, photo, pad.toFloat(), palette)
        decor.stamp?.let { drawStamp(canvas, it, decor.stampCorner, photo, palette) }
        return out
    }

    /**
     * Тёплый фильтр «книжной сепии»: сначала обесцветить, потом прогреть каналы
     * в тона бумаги. Тот же характер, что у стареющего [com.polinalinen.madre
     * .ui.components.PhotoAging], но мягче — снимок только что вклеили, он ещё
     * не успел выцвести.
     */
    private fun warmMatrix(): ColorMatrix {
        val warm = ColorMatrix(
            floatArrayOf(
                1.06f, 0f, 0f, 0f, 6f,
                0f, 0.98f, 0f, 0f, 2f,
                0f, 0f, 0.84f, 0f, -6f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        // Не в ноль: цвет хлеба должен остаться узнаваемым, это не «старое фото».
        warm.preConcat(ColorMatrix().apply { setSaturation(0.35f) })
        return warm
    }

    private fun drawFrame(canvas: Canvas, frame: PhotoFrame, photo: RectF, pad: Float, palette: Palette) {
        val hairline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = palette.ink
            alpha = 90
            strokeWidth = max(1f, pad * 0.06f)
        }
        when (frame) {
            PhotoFrame.NONE -> Unit
            // Паспарту: волосяная линейка по обрезу снимка — как у наклеенной
            // в альбом карточки, где картон чуть темнее по краю выреза.
            PhotoFrame.MAT -> canvas.drawRect(photo, hairline)
            // Рваный край: бумагу обрывали руками, а не резали ножом.
            PhotoFrame.DECKLE -> {
                canvas.drawPath(decklePath(canvas.width.toFloat(), canvas.height.toFloat(), pad * 0.45f), Paint().apply {
                    style = Paint.Style.STROKE
                    strokeWidth = pad * 0.5f
                    color = palette.paper
                    isAntiAlias = true
                })
                canvas.drawRect(photo, hairline)
            }
            // Уголки-держатели — тот же мотив, что drawPhotoHolders в книге.
            PhotoFrame.HOLDERS -> {
                val holder = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = palette.accent
                    alpha = 150
                }
                val side = min(photo.width(), photo.height()) * 0.13f
                corner(canvas, holder, photo.left, photo.top, side, side)
                corner(canvas, holder, photo.right, photo.top, -side, side)
                corner(canvas, holder, photo.left, photo.bottom, side, -side)
                corner(canvas, holder, photo.right, photo.bottom, -side, -side)
            }
        }
    }

    private fun corner(canvas: Canvas, paint: Paint, x: Float, y: Float, dx: Float, dy: Float) {
        val path = Path().apply {
            moveTo(x, y)
            lineTo(x + dx, y)
            lineTo(x, y + dy)
            close()
        }
        canvas.drawPath(path, paint)
    }

    /**
     * Обрыв бумаги — замкнутая ломаная по периметру с детерминированным
     * дрожанием. Seed фиксирован: одна и та же карточка не должна «шевелиться»
     * между превью и сохранением.
     */
    private fun decklePath(width: Float, height: Float, amplitude: Float): Path {
        val random = java.util.Random(0x0DEC1E)
        val step = max(width, height) / 48f
        val path = Path()
        var first = true
        fun wobble() = (random.nextFloat() - 0.5f) * 2f * amplitude
        fun point(x: Float, y: Float) {
            if (first) {
                path.moveTo(x, y)
                first = false
            } else {
                path.lineTo(x, y)
            }
        }
        var x = 0f
        while (x < width) { point(x, wobble().coerceAtLeast(0f)); x += step }
        var y = 0f
        while (y < height) { point(width + wobble().coerceAtMost(0f), y); y += step }
        x = width
        while (x > 0f) { point(x, height + wobble().coerceAtMost(0f)); x -= step }
        y = height
        while (y > 0f) { point(wobble().coerceAtLeast(0f), y); y -= step }
        path.close()
        return path
    }

    private fun drawStamp(canvas: Canvas, stamp: PhotoStamp, corner: StampCorner, photo: RectF, palette: Palette) {
        val radius = min(photo.width(), photo.height()) * STAMP_FRACTION
        val inset = radius * 1.35f
        val cx = when (corner) {
            StampCorner.TOP_LEFT, StampCorner.BOTTOM_LEFT -> photo.left + inset
            StampCorner.TOP_RIGHT, StampCorner.BOTTOM_RIGHT -> photo.right - inset
        }
        val cy = when (corner) {
            StampCorner.TOP_LEFT, StampCorner.TOP_RIGHT -> photo.top + inset
            StampCorner.BOTTOM_LEFT, StampCorner.BOTTOM_RIGHT -> photo.bottom - inset
        }

        // Оттиск лежит на светлом пятне — иначе тёмный снимок съедает рисунок.
        canvas.drawCircle(cx, cy, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.cream
            alpha = 205
        })
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1.5f, radius * 0.085f)
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            color = palette.ink
        }
        canvas.drawCircle(cx, cy, radius * 0.92f, ink)
        when (stamp) {
            PhotoStamp.WHEAT -> drawWheat(canvas, cx, cy, radius, ink)
            PhotoStamp.LOAF -> drawLoaf(canvas, cx, cy, radius, ink)
            PhotoStamp.ROSETTE -> drawRosette(canvas, cx, cy, radius, ink)
        }
    }

    /** Колос: стебель и пять пар зёрен, раскрытых вверх. */
    private fun drawWheat(canvas: Canvas, cx: Float, cy: Float, r: Float, ink: Paint) {
        canvas.drawLine(cx, cy + r * 0.58f, cx, cy - r * 0.30f, ink)
        val grain = r * 0.26f
        for (i in 0 until 5) {
            val y = cy + r * 0.40f - i * r * 0.20f
            val path = Path().apply {
                moveTo(cx, y)
                quadTo(cx - grain, y - grain * 0.35f, cx - grain * 0.35f, y - grain * 0.95f)
            }
            canvas.drawPath(path, ink)
            val mirrored = Path().apply {
                moveTo(cx, y)
                quadTo(cx + grain, y - grain * 0.35f, cx + grain * 0.35f, y - grain * 0.95f)
            }
            canvas.drawPath(mirrored, ink)
        }
    }

    /** Каравай: купол на поду и три надреза сверху. */
    private fun drawLoaf(canvas: Canvas, cx: Float, cy: Float, r: Float, ink: Paint) {
        val dome = RectF(cx - r * 0.62f, cy - r * 0.42f, cx + r * 0.62f, cy + r * 0.62f)
        canvas.drawArc(dome, 180f, 180f, false, ink)
        canvas.drawLine(cx - r * 0.62f, cy + r * 0.10f, cx + r * 0.62f, cy + r * 0.10f, ink)
        for (i in -1..1) {
            val x = cx + i * r * 0.30f
            canvas.drawLine(x - r * 0.10f, cy - r * 0.20f, x + r * 0.10f, cy - r * 0.02f, ink)
        }
    }

    /** Розетка: сердцевина и восемь лепестков — мотив сургучной печати книги. */
    private fun drawRosette(canvas: Canvas, cx: Float, cy: Float, r: Float, ink: Paint) {
        canvas.drawCircle(cx, cy, r * 0.20f, ink)
        val petal = r * 0.30f
        for (i in 0 until 8) {
            val angle = Math.toRadians(i * 45.0)
            val px = cx + (r * 0.48f * Math.cos(angle)).toFloat()
            val py = cy + (r * 0.48f * Math.sin(angle)).toFloat()
            canvas.drawCircle(px, py, petal * 0.62f, ink)
        }
    }
}
