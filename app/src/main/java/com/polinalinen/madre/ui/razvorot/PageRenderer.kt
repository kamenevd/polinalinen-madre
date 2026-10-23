package com.polinalinen.madre.ui.razvorot

import com.polinalinen.madre.ui.kit.*
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
    private val roman = PaperFonts.roman(context)
    private val light = PaperFonts.light(context)
    private val medium = PaperFonts.medium(context)
    private val italic = PaperFonts.italic(context)
    private val ui = PaperFonts.ui(context)
    fun text(recipe: Recipe, chapter: Int, width: Int, height: Int, colors: PaperColors, fontSize: Float = 16f, scale: Double = 1.0, startStep: Int = 1, viewportWidth:Float=360f): Bitmap {
        val bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
        val canvas=Canvas(bitmap);val density=width/viewportWidth;canvas.scale(density,density)
        val bodyWidth=(viewportWidth-106f).toInt().coerceAtLeast(1)
        val h=height/density;canvas.drawColor(colors.bg.toArgb())
        val paint=TextPaint(Paint.ANTI_ALIAS_FLAG).apply{typeface=roman;color=colors.ink2.toArgb();textSize=fontSize}
        fun paragraph(value:String,x:Float,y:Float,w:Int,font:Float=fontSize,face:Typeface=roman,color:Int=colors.ink2.toArgb(),leading:Float=font*25f/16f):Float {
            paint.textSize=font;paint.typeface=face;paint.color=color;paint.letterSpacing=if(face==ui)1.8f/font else 0f
            val natural=paint.fontMetricsInt.let{it.descent-it.ascent}
            val layout=StaticLayout.Builder.obtain(value,0,value.length,paint,w).setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).setLineSpacing(leading-natural,1f).build()
            canvas.save();canvas.translate(x,y);layout.draw(canvas);canvas.restore();return layout.height.toFloat()
        }
        paragraph("ГЛАВА %02d · %s".format(chapter+1,recipe.name.uppercase()),26f,22f,(viewportWidth-52f).toInt(),10f,ui)
        var y=68f
        val bindings=RecipeScaler.scalableBindings(recipe)
        for(i in startStep until recipe.timeline.size) {
            val step=recipe.timeline[i]
            val body=RecipeScaler.scaledStepText(step.description,scale,bindings)
            paint.textSize=fontSize;paint.typeface=roman;paint.letterSpacing=0f
            val measure=StaticLayout.Builder.obtain(body,0,body.length,paint,bodyWidth).setIncludePad(false).setLineSpacing(fontSize*25f/16f-(paint.fontMetricsInt.descent-paint.fontMetricsInt.ascent),1f).build()
            if(y+measure.height+65f>h-45f)break
            paragraph("%02d".format(i+1),26f,y,48,38f,light,colors.mut.toArgb())
            y+=paragraph(stepOverline(recipe,i).uppercase(context.resources.configuration.locales[0]),80f,y,bodyWidth,10f,ui)+4f
            y+=paragraph(step.title,80f,y,bodyWidth,18f,if(step.type==com.polinalinen.madre.model.StepType.WAIT)italic else medium,colors.ink.toArgb(),23f)+4f
            y+=paragraph(body,80f,y,bodyWidth)+24f
        }
        paint.textSize=11f;paint.typeface=ui;paint.color=colors.ink2.toArgb();paint.letterSpacing=1.8f/11f
        // Explicit deterministic fixture position, separate aligned elements;
        // runtime PageFooter uses the real current chapter/leaf counts.
        paint.textAlign=Paint.Align.CENTER;canvas.drawText("3",viewportWidth/2f,h-20f,paint)
        paint.textAlign=Paint.Align.RIGHT;canvas.drawText("3 / 5",viewportWidth-26f,h-20f,paint)
        return bitmap
    }
}
