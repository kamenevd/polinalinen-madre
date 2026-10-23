package com.polinalinen.madre.ui.razvorot

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.compose.ui.graphics.toArgb
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import kotlin.math.PI
import kotlin.math.roundToInt

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35])
class PaperMaterialReviewTest {
    @Test fun exactV3PaperAndFaintMirroredPrintSurviveTheMesh() {
        val paper=DawnPaper.bg.toArgb()
        val front=Bitmap.createBitmap(360,740,Bitmap.Config.ARGB_8888).apply{eraseColor(paper)}
        Canvas(front).drawRect(310f,300f,350f,400f,Paint().apply{color=Color.BLACK})
        val under=Bitmap.createBitmap(360,740,Bitmap.Config.ARGB_8888).apply{eraseColor(paper)}
        val target=Bitmap.createBitmap(360,740,Bitmap.Config.ARGB_8888)
        val fold=PaperFold.fromFinger(360f,740f,1,350f,370f,60f,370f)
        val texture=PaperTexture(front,paper)
        try {
            texture.draw(Canvas(target),fold,under)
            val mirrored=fold.point(330f,350f)
            assertThat(mirrored.theta).isEqualTo(PI.toFloat())
            assertThat(mirrored.x).isLessThan(330f)
            val printed=target.getPixel(mirrored.x.roundToInt(),mirrored.y.roundToInt())
            val blank=target.getPixel(mirrored.x.roundToInt(),fold.point(330f,500f).y.roundToInt())
            assertThat(Color.red(blank)).isGreaterThan(Color.blue(blank))
            // New audit restores PAGE-CURLv3:0.60→0.80,desat45%,tint0.92.
            // A far flat verso is dark paper, not the superseded0.84→0.96.
            assertThat(Color.red(blank)).isLessThan((Color.red(paper)*.81f).toInt())
            assertThat(Color.red(blank)).isGreaterThan((Color.red(paper)*.58f).toInt())
            assertThat(Color.red(printed)).isLessThan(Color.red(blank))
            assertThat(Color.red(blank)-Color.red(printed)).isAtMost(25) // faint 7% print, not legible face ink
        } finally { texture.close();front.recycle();under.recycle();target.recycle() }
    }
    @Test fun runtimeShaderUsesExactV3CoefficientsAndNeverLightsTheVerso() {
        val shader=PaperGlView.FS
        listOf(PaperLight.FACE,PaperLight.AO,PaperLight.NIGHT,PaperLight.HIGHLIGHT,PaperLight.SHADOW_START,PaperLight.SHADOW_RADIUS,PaperLight.SHADOW_FADE,PaperLight.SHADOW,PaperLight.SHADOW_MAX,PaperLight.DESATURATION,PaperLight.PRINT,PaperLight.VERSO_BASE,PaperLight.VERSO_RANGE,PaperLight.VERSO_TINT,PaperLight.VERSO_NIGHT).forEach {
            assertThat(shader).contains(it.toString())
        }
        for(i in 0..100) {
            val theta=(PI/2+PI/2*i/100).toFloat()
            assertThat(PaperLight.versoShade(theta)).isAtMost(1f)
        }
    }
    @Test fun jarLevelUsesTheSelectedFeedingProfile() {
        val day=com.polinalinen.madre.sourdough.profileForInterval(24)
        val fridge=com.polinalinen.madre.sourdough.profileForInterval(72)
        assertThat(starterCycleLevel(12f,day.peakHours,day.cycleHours)).isNotEqualTo(starterCycleLevel(12f,fridge.peakHours,fridge.cycleHours))
        assertThat(starterCycleLevel(null,day.peakHours,day.cycleHours)).isEqualTo(0f)
    }
    @Test fun consumedClearCannotAcknowledgeSkippedPaperBuffer() {
        val frames=PaperPresentation()
        frames.queued(10L,true);frames.queued(20L,false)
        assertThat(frames.consumed(20L)).isFalse()
        assertThat(frames.consumed(10L)).isFalse()
        frames.queued(30L,true)
        assertThat(frames.consumed(30L)).isTrue()
        assertThat(frames.consumed(30L)).isFalse()
        assertThat(frames.consumed(40L)).isFalse()
    }
}
