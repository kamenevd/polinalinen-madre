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
    @Test fun warmPaperAndFaintMirroredPrintSurviveTheMesh() {
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
            // Designer's warm-paper review replaces the old ~0.74 grey verso.
            assertThat(Color.red(blank)).isGreaterThan((Color.red(paper)*.85f).toInt())
            assertThat(Color.red(printed)).isLessThan(Color.red(blank))
            assertThat(Color.red(blank)-Color.red(printed)).isAtMost(25) // faint 7% print, not legible face ink
        } finally { texture.close();front.recycle();under.recycle();target.recycle() }
    }
    @Test fun runtimeShaderUsesTheSameReviewCoefficientsAndNeverLightsTheVerso() {
        val shader=PaperGlView.FS
        listOf(PaperLight.SHADOW,PaperLight.SHADOW_MAX,PaperLight.DESATURATION,PaperLight.PRINT,PaperLight.VERSO_BASE,PaperLight.VERSO_RANGE).forEach {
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
}
