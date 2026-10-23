package com.polinalinen.madre.ui.razvorot

import android.graphics.Bitmap

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import kotlin.math.*

internal data class CurlPoint(val x: Float, val y: Float, val z: Float, val theta: Float, val d: Float)
internal fun PaperFold.point(x: Float, y: Float): CurlPoint {
    val d = d(x, y)
    if (d <= 0f) return CurlPoint(x, y, 0f, 0f, d)
    val theta = (d / radius).coerceAtMost(PI.toFloat())
    val along = if (d < PI * radius) radius * sin(theta) else -(d - PI.toFloat() * radius)
    val z = if (d < PI * radius) radius * (1f - cos(theta)) else 2f * radius
    val xx = x - nx * d + nx * along
    val yy = y - ny * d + ny * along
    val perspective = 1f + z / 3400f
    return CurlPoint(width / 2f + (xx - width / 2f) * perspective, height / 2f + (yy - height / 2f) * perspective, z, theta, d)
}

/** Deterministic native CPU renderer for screenshot fixtures; runtime without GL
 * uses the flat slide, not this mesh (designer Q2). UVs deform with the sheet,
 * so verso print is genuinely mirrored rather than a separate flat label. */
internal class PaperTexture(val front: Bitmap, paper: Int, private val night: Boolean = false) {
    private val verso = Bitmap.createBitmap(front.width, front.height, Bitmap.Config.ARGB_8888).apply {
        val gray = (Color.red(paper) + Color.green(paper) + Color.blue(paper)) / 3f
        val multiplier = if (night) 1.22f else .92f
        fun channel(v: Int) = ((v * .55f + gray * .45f) * multiplier).toInt().coerceIn(0, 255)
        val c = Canvas(this)
        c.drawColor(Color.rgb(channel(Color.red(paper)), channel(Color.green(paper)), channel(Color.blue(paper))))
        c.drawBitmap(front, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 18 })
    }
    fun draw(canvas: Canvas, fold: PaperFold, under: Bitmap? = null, grid: Boolean = false) {
        val nx = 24; val ny = 48; val n = (nx + 1) * (ny + 1)
        val vertices = FloatArray(n * 2); val flat = FloatArray(n * 2)
        val face = IntArray(n); val back = IntArray(n); val shadow = IntArray(n)
        val scaleX = front.width / fold.width; val scaleY = front.height / fold.height
        val k = if (night) 1.6f else 1f
        fun rgb(v: Float, visible: Boolean = true): Int {
            val c = (255f * v.coerceIn(0f, 1f)).toInt()
            return Color.argb(if (visible) 255 else 0, c, c, c)
        }
        for (i in 0 until n) {
            val x = fold.width * (i % (nx + 1)) / nx
            val y = fold.height * (i / (nx + 1)) / ny
            val v = fold.point(x, y)
            vertices[2 * i] = v.x * scaleX; vertices[2 * i + 1] = v.y * scaleY
            flat[2 * i] = x * scaleX; flat[2 * i + 1] = y * scaleY
            val crease = 1f - .24f * k * exp(-abs(v.d) / (.45f * fold.radius + 6f))
            val spec = .035f * (if (night) .4f else 1f) * exp(-((v.theta - 1.12f) / .06f).pow(2))
            face[i] = rgb((1f - .40f * (1f - cos(v.theta)) + spec) * crease, v.theta <= PI / 2)
            back[i] = rgb((.60f + .20f * -cos(v.theta)) * crease, v.theta > PI / 2)
            val e = v.d - .9f * fold.radius
            val t = ((e + 1.75f * fold.radius) / (1.75f * fold.radius)).coerceIn(0f, 1f)
            val smooth = t * t * (3f - 2f * t)
            val s = .58f * k * exp(-max(e, 0f) / (1.1f * fold.radius + 20f)) * if (e < 0f) smooth else 1f
            shadow[i] = rgb(1f - min(s, .8f))
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        if (under != null) canvas.drawBitmapMesh(under, nx, ny, flat, 0, shadow, 0, paint)
        canvas.drawBitmapMesh(front, nx, ny, vertices, 0, face, 0, paint)
        canvas.drawBitmapMesh(verso, nx, ny, vertices, 0, back, 0, paint)
        if (grid) {
            paint.color = Color.RED; paint.strokeWidth = 1f
            for (y in 0..ny) for (x in 0 until nx) { val i = y * (nx + 1) + x; canvas.drawLine(vertices[2*i], vertices[2*i+1], vertices[2*i+2], vertices[2*i+3], paint) }
        }
    }
    fun close() { verso.recycle() }
}
