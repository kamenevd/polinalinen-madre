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
internal class PaperTexture(val front: Bitmap, private val paper: Int, private val night: Boolean = false) {
    private class Material(val fold:PaperFold,val sourceUnder:Bitmap?,val face:Bitmap,val back:Bitmap,val under:Bitmap?) {
        fun close(){face.recycle();back.recycle();under?.recycle()}
    }
    private var material:Material?=null
    /** Shade source texels, not only the24 mesh columns. The canonical rim has
     * angular width0.06rad: interpolating vertex colours erased it and smeared
     * face/verso masks across an entire cell. Geometry remains24x48. */
    private fun prepare(fold:PaperFold,under:Bitmap?):Material {
        material?.let{if(it.fold===fold && it.sourceUnder===under)return it}
        material?.close()
        val w=front.width;val h=front.height;val count=w*h
        val source=IntArray(count);front.getPixels(source,0,w,0,0,w,h)
        val lower=under?.let{IntArray(count).also{p->it.getPixels(p,0,w,0,0,w,h)}}
        val face=IntArray(count);val back=IntArray(count);val receiver=lower?.let{IntArray(count)}
        val gray=(Color.red(paper)+Color.green(paper)+Color.blue(paper))/3f
        val tint=PaperLight.VERSO_TINT+if(night)PaperLight.VERSO_NIGHT else 0f
        fun paperChannel(value:Int)=(value*(1f-PaperLight.DESATURATION)+gray*PaperLight.DESATURATION)*tint+if(night)PaperLight.VERSO_NIGHT_LIFT*255f else 0f
        val pr=paperChannel(Color.red(paper));val pg=paperChannel(Color.green(paper));val pb=paperChannel(Color.blue(paper))
        fun rgb(r:Float,g:Float,b:Float,shade:Float,grain:Float)=Color.rgb(
            (r*shade+grain).roundToInt().coerceIn(0,255),
            (g*shade+grain).roundToInt().coerceIn(0,255),
            (b*shade+grain).roundToInt().coerceIn(0,255))
        for(y in 0 until h)for(x in 0 until w) {
            val sx=(x+.5f)*fold.width/w;val sy=(y+.5f)*fold.height/h
            val d=fold.d(sx,sy);val theta=(max(d,0f)/fold.radius).coerceAtMost(PI.toFloat())
            val crease=PaperLight.crease(d,fold.radius,night)
            val grain=PaperLight.grain(sx.toInt(),sy.toInt())*255f
            val i=y*w+x;val tex=source[i]
            if(theta<=PI/2) {
                val light=if(d<=0f)1f else PaperLight.faceShade(theta)+PaperLight.highlight(theta,night)
                face[i]=rgb(Color.red(tex).toFloat(),Color.green(tex).toFloat(),Color.blue(tex).toFloat(),light*crease,grain)
            } else {
                val light=PaperLight.versoShade(theta)*crease
                fun print(base:Float,value:Int)=base*(1f-PaperLight.PRINT)+value*PaperLight.PRINT
                back[i]=rgb(print(pr,Color.red(tex)),print(pg,Color.green(tex)),print(pb,Color.blue(tex)),light,grain)
            }
            if(lower!=null && receiver!=null) {
                val c=lower[i];val light=PaperLight.receiver(d,fold.radius,night)
                receiver[i]=rgb(Color.red(c).toFloat(),Color.green(c).toFloat(),Color.blue(c).toFloat(),light,grain)
            }
        }
        fun bitmap(pixels:IntArray)=Bitmap.createBitmap(pixels,w,h,Bitmap.Config.ARGB_8888)
        return Material(fold,under,bitmap(face),bitmap(back),receiver?.let{bitmap(it)}).also{material=it}
    }
    fun draw(canvas: Canvas, fold: PaperFold, under: Bitmap? = null, grid: Boolean = false) {
        val nx = 24; val ny = 48; val n = (nx + 1) * (ny + 1)
        val vertices = FloatArray(n * 2); val flat = FloatArray(n * 2)
        val scaleX = front.width / fold.width; val scaleY = front.height / fold.height
        for (i in 0 until n) {
            val x = fold.width * (i % (nx + 1)) / nx
            val y = fold.height * (i / (nx + 1)) / ny
            val v = fold.point(x, y)
            vertices[2 * i] = v.x * scaleX; vertices[2 * i + 1] = v.y * scaleY
            flat[2 * i] = x * scaleX; flat[2 * i + 1] = y * scaleY
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val shaded=prepare(fold,under)
        shaded.under?.let{canvas.drawBitmapMesh(it,nx,ny,flat,0,null,0,paint)}
        canvas.drawBitmapMesh(shaded.face,nx,ny,vertices,0,null,0,paint)
        canvas.drawBitmapMesh(shaded.back,nx,ny,vertices,0,null,0,paint)
        if (grid) {
            paint.color = Color.RED; paint.strokeWidth = 1f
            for (y in 0..ny) for (x in 0 until nx) { val i = y * (nx + 1) + x; canvas.drawLine(vertices[2*i], vertices[2*i+1], vertices[2*i+2], vertices[2*i+3], paint) }
        }
    }
    fun close() { material?.close();material=null }
}
