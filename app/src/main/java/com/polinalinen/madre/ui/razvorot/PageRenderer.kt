package com.polinalinen.madre.ui.razvorot

import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.content.res.ResourcesCompat
import androidx.compose.ui.graphics.toArgb
import com.polinalinen.madre.R
import com.polinalinen.madre.model.Recipe
import com.polinalinen.madre.model.RecipeScaler

/** Native page texture renderer. Draws live recipe strings, not an exported UI image. */
internal class PageRenderer(private val context: Context) {
    private val roman = ResourcesCompat.getFont(context,R.font.literata) ?: Typeface.SERIF
    private val ui = ResourcesCompat.getFont(context,R.font.manrope) ?: Typeface.SANS_SERIF
    fun text(recipe: Recipe, chapter: Int, width: Int, height: Int, colors: PaperColors, fontSize: Float = 16f, scale: Double = 1.0, startStep: Int = 1, viewportWidth:Float=360f): Bitmap {
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);val density=width/viewportWidth;canvas.scale(density,density)
        val bodyWidth=(viewportWidth-106f).toInt().coerceAtLeast(1)
        val h=height/density;canvas.drawColor(colors.bg.toArgb())
        val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply{typeface=roman;color=colors.secondary.toArgb();textSize=fontSize}
        fun paragraph(value:String,x:Float,y:Float,w:Int,font:Float=fontSize,face:Typeface=roman,color:Int=colors.secondary.toArgb()):Float {
            paint.textSize=font;paint.typeface=face;paint.color=color
            val layout=StaticLayout.Builder.obtain(value,0,value.length,paint,w).setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(0f,1.55f).build()
            canvas.save();canvas.translate(x,y);layout.draw(canvas);canvas.restore();return layout.height.toFloat()
        }
        paragraph("ГЛАВА %02d · %s".format(chapter+1,recipe.name.uppercase()),26f,22f,(viewportWidth-52f).toInt(),10f,ui)
        var y=68f
        val bindings=RecipeScaler.scalableBindings(recipe)
        for(i in startStep until recipe.timeline.size) {
            val step=recipe.timeline[i]
            val body=RecipeScaler.scaledStepText(step.description,scale,bindings)
            paint.textSize=fontSize;paint.typeface=roman
            val measure=StaticLayout.Builder.obtain(body,0,body.length,paint,bodyWidth).setIncludePad(false).setLineSpacing(0f,1.55f).build()
            if(y+measure.height+65f>h-45f)break
            paragraph("%02d".format(i+1),24f,y,48,38f,roman)
            y+=paragraph("${step.durationMinutes} МИН",80f,y,bodyWidth,10f,ui)+4f
            y+=paragraph(step.title,80f,y,bodyWidth,fontSize+2f,roman,colors.ink.toArgb())+4f
            y+=paragraph(body,80f,y,bodyWidth)+24f
        }
        paint.textSize=11f;paint.typeface=ui;paint.color=colors.secondary.toArgb()
        canvas.drawText("3",viewportWidth/2f-5f,h-20f,paint);canvas.drawText("3 / 5",viewportWidth-54f,h-20f,paint)
        return bitmap
    }
}
