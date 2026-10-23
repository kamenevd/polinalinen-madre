package com.polinalinen.madre.ui.razvorot

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import kotlin.math.*

/** Cylindrical paper, in page pixels. The grabbed edge reaches the finger;
 * radius is deliberately broad, not a rigid rotation or a sharp dog-ear. */
internal data class CurlPoint(val x:Float,val y:Float,val z:Float,val normalZ:Float)
internal class PaperCylinder(val width:Float,val height:Float,progress:Float,grip:Float,val reverse:Boolean) {
    val radius = PaperGeometry.radius(width, progress)
    private val travel=2f*width*progress.coerceIn(0f,1f)
    private val vertical=(grip-.5f)*travel*.22f
    private val length=hypot(travel,vertical).coerceAtLeast(.0001f)
    private val nx=travel/length
    private val ny=vertical/length
    private val distance:Float
    private val px:Float
    private val py:Float
    init {
        val q=length/radius
        var low=0f;var high=PI.toFloat()
        repeat(24){val mid=(low+high)*.5f;if(mid-sin(mid)<q)low=mid else high=mid}
        distance=if(q<PI)radius*(low+high)*.5f else (length+PI.toFloat()*radius)*.5f
        px=width-nx*distance;py=grip*height-ny*distance
    }
    fun point(sourceX:Float,sourceY:Float):CurlPoint {
        val x=if(reverse)width-sourceX else sourceX
        val d=(x-px)*nx+(sourceY-py)*ny
        if(d<=0f)return CurlPoint(sourceX,sourceY,0f,1f)
        val bx=x-nx*d;val by=sourceY-ny*d
        val turn=(d/radius).coerceAtMost(PI.toFloat())
        val along=if(d<PI*radius)radius*sin(turn) else -(d-PI.toFloat()*radius)
        val z=if(d<PI*radius)radius*(1f-cos(turn)) else 2f*radius
        val xx=bx+nx*along;val yy=by+ny*along
        val scale=1f+z/(width*9f)
        val projectedX=width*.5f+(xx-width*.5f)*scale
        return CurlPoint(if(reverse)width-projectedX else projectedX,height*.5f+(yy-height*.5f)*scale,z,cos(turn))
    }
}

/** Native Android mesh; front and translucent-print verso are actual current
 * Compose content captured once per gesture, never HTML or a static UI plate. */
internal class PaperTexture(val front:Bitmap,paper:Int) {
    private val verso=Bitmap.createBitmap(front.width,front.height,Bitmap.Config.ARGB_8888).apply {
        val c=Canvas(this);c.drawColor(paper)
        c.drawBitmap(front,0f,0f,Paint().apply{alpha=18})
    }
    private val frontPaint=Paint(Paint.FILTER_BITMAP_FLAG).apply{shader=BitmapShader(front,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP)}
    private val backPaint=Paint(Paint.FILTER_BITMAP_FLAG).apply{shader=BitmapShader(verso,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP)}
    fun draw(canvas:Canvas,cylinder:PaperCylinder) {
        val nx=32;val ny=44
        val points=Array((nx+1)*(ny+1)){i->cylinder.point(front.width*(i%(nx+1)).toFloat()/nx,front.height*(i/(nx+1)).toFloat()/ny)}
        fun surface(back:Boolean) {
            val verts=ArrayList<Float>();val tex=ArrayList<Float>();val colors=ArrayList<Int>()
            fun vertex(i:Int){val v=points[i];verts.add(v.x);verts.add(v.y);tex.add(front.width*(i%(nx+1)).toFloat()/nx);tex.add(front.height*(i/(nx+1)).toFloat()/ny)
                val shade=(.62f+.38f*abs(v.normalZ)).coerceIn(0f,1f);val n=(shade*255).toInt();colors.add(android.graphics.Color.rgb(n,n,n))}
            for(y in 0 until ny)for(x in 0 until nx){val a=y*(nx+1)+x;val b=a+1;val c=a+nx+1;val d=c+1
                for(tri in listOf(intArrayOf(a,b,c),intArrayOf(b,d,c))){if((tri.sumOf{points[it].normalZ.toDouble()}<0)==back)tri.forEach{vertex(it)}}}
            if(verts.isNotEmpty())canvas.drawVertices(Canvas.VertexMode.TRIANGLES,verts.size,verts.toFloatArray(),0,tex.toFloatArray(),0,colors.toIntArray(),0,null,0,0,if(back)backPaint else frontPaint)
        }
        surface(false);surface(true)
    }
    fun close(){verso.recycle();front.recycle()}
}
