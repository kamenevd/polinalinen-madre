package com.polinalinen.madre.ui.razvorot

import com.polinalinen.madre.ui.kit.*
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.*
import com.polinalinen.madre.MadreApplication
import com.polinalinen.madre.data.repository.RecipeRepository
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import com.google.common.truth.Truth.assertThat

/** Manual/nightly/RC ONLY. Extended native F-v3 evidence, NOT a replacement for approved historical goldens.
 * Output lives under build/ and must be reviewed before becoming a pixel baseline. */
@OptIn(ExperimentalRoborazziApi::class)
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk=[35],qualifiers="ru-w412dp-h915dp-mdpi",application=MadreApplication::class)
class RazvorotExtendedRenderTest {
    private fun render(scene:String) {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val recipes=runBlocking{RecipeRepository(context).getRecipes()}
        val module=if(File("app/src/main/AndroidManifest.xml").isFile)File("app") else File(".")
        fun capture(theme:Atmosphere?) {
        val directory=if(theme==null)"f-v3" else "f-v3-matrix/${theme.captureName()}"
        val filename=if(theme==null)"$scene.png" else "$scene-${theme.captureName()}.png"
        val output=File(module,"build/outputs/roborazzi/$directory/$filename").absoluteFile
        output.parentFile.mkdirs()
        captureRoboImage(filePath=output.path,roborazziOptions=RoborazziOptions(taskType=RoborazziTaskType.Record)) {
            RazvorotDebugScene(scene,recipes,theme)
        }
        assertThat(output.isFile).isTrue()
        assertThat(output.length()).isGreaterThan(1000L)
        val bitmap=android.graphics.BitmapFactory.decodeFile(output.path)
        assertThat(bitmap.width).isEqualTo(412)
        assertThat(bitmap.height).isEqualTo(915)
        if(scene=="curl-mid" || scene=="curl-back") {
            val fold=debugCurlFold(412f,915f,scene)
            val xs=listOf(1.7f,1.9f,2.3f,2.7f,3.05f).map{theta->fold.point(fold.px+theta*fold.radius,fold.height/2).x.toInt().coerceIn(0,411)}
            val medians=xs.map{x->(640..820).map{android.graphics.Color.red(bitmap.getPixel(x,it))}.sorted()[90]}
            val night=theme==Atmosphere.NIGHT
            assertThat(medians.max()-medians.min()).isAtLeast(if(night)1 else 10)
            val highlight=fold.point(fold.px+PaperLight.HIGHLIGHT_ANGLE*fold.radius,fold.height/2).x
            val edge=fold.point(fold.px+(kotlin.math.PI.toFloat()/2)*fold.radius,fold.height/2).x.toInt()
            fun medianAt(x:Int)=(640..820).map{android.graphics.Color.red(bitmap.getPixel(x.coerceIn(0,411),it))}.sorted()[90]
            val edgeInside=medianAt(edge-2);val edgeOutside=medianAt(edge+4)
            if(night)assertThat(edgeInside-edgeOutside).isAtLeast(2)
            File(output.parentFile,output.nameWithoutExtension+"-pixel-profile.json").writeText("{\"renderer\":\"CPU24x48-native\",\"theme\":\"${theme?.captureName()}\",\"projectedX\":$xs,\"medianR\":$medians,\"highlightTheta\":1.12,\"highlightProjectedX\":$highlight,\"silhouetteX\":$edge,\"edgeInsideR\":$edgeInside,\"edgeOutsideR\":$edgeOutside}")
        }
        bitmap.recycle()
        }
        capture(null)
        listOf(Atmosphere.DAWN,Atmosphere.DAY,Atmosphere.DUSK,Atmosphere.NIGHT).forEach{capture(it)}
    }
    @Test fun titleMorning()=render("reader-title-dawn")
    @Test fun titleNight()=render("reader-title-night")
    @Test fun text16()=render("reader-text-16")
    @Test fun text22()=render("reader-text-22")
    @Test fun curlTop()=render("curl-top")
    @Test fun curlMiddle()=render("curl-mid")
    @Test fun curlBottom()=render("curl-bottom")
    @Test fun curlBack()=render("curl-back")
    @Test fun chrome()=render("reader-chrome")
    @Test fun readingSettings()=render("reading-sheet")
    @Test fun homeMorning()=render("home-dawn")
    @Test fun homeNight()=render("home-night")
    @Test fun catalog()=render("catalog")
    @Test fun staticFontsCyrillic()=render("font-compat")
    @Test fun spine()=render("spine")
    @Test fun bake()=render("bake")
    @Test fun oven()=render("oven")
    @Test fun bakeMore()=render("bake-more")
    @Test fun bakeFinale()=render("bake-finale")
    @Test fun navChapterTitle()=render("chapter-title")
    @Test fun navToc()=render("toc")
    @Test fun optionalYandexOffer()=render("yandex-offer")
    @Test fun explicitYandexVoiceConsent()=render("alice-yandex-consent")
    @Test fun settings()=render("settings")
    @Test fun settingsSearch()=render("settings-search")
    @Test fun aliceListeningDebug()=render("alice-listening")
    @Test fun aliceReadingDebug()=render("alice-reading")
    @Test fun aliceConfirmDebug()=render("alice-confirm")
    @Test fun aliceNightDebug()=render("alice-night")
    @Test fun starterGrowing()=render("starter-growing")
    @Test fun starterPeak()=render("starter-peak")
    @Test fun starterHungry()=render("starter-hungry")
    @Test fun shelf()=render("shelf")
    @Test fun shelfNight()=render("shelf-night")
    @Test fun shelfLargeType()=render("shelf-large")
    @Test fun curlNight()=render("curl-mid-night")
    @Test fun bakeStart()=render("bake-start")
    @Test fun bakeEnd()=render("bake-end")
    @Test fun fullTimerStart()=render("fulltimer-start")
    @Test fun fullTimerEnd()=render("fulltimer-end")
    @Test fun variableAxisReallyThickensTheSameDigits() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val bytes=context.resources.openRawResource(com.polinalinen.madre.R.font.manrope).use{it.readBytes()}
        val buffer=java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.BIG_ENDIAN)
        fun u16(p:Int)=buffer.getShort(p).toInt() and 65535
        val table=(0 until u16(4)).first {String(bytes,12+it*16,4,Charsets.US_ASCII)=="fvar"}
        val fvar=buffer.getInt(12+table*16+8)
        val axes=fvar+u16(fvar+4);val axisSize=u16(fvar+10)
        val weight=(0 until u16(fvar+8)).first{String(bytes,axes+it*axisSize,4,Charsets.US_ASCII)=="wght"}
        assertThat(buffer.getInt(axes+weight*axisSize+4)/65536f).isEqualTo(200f)
        assertThat(buffer.getInt(axes+weight*axisSize+12)/65536f).isEqualTo(800f)
        render("timer-weight-start");render("timer-weight-end")
        val module=if(File("app/src/main/AndroidManifest.xml").isFile)File("app") else File(".")
        fun ink(scene:String):Int {
            val bitmap=android.graphics.BitmapFactory.decodeFile(File(module,"build/outputs/roborazzi/f-v3/$scene.png").absolutePath)
            var count=0
            for(y in 0 until bitmap.height)for(x in 0 until bitmap.width) {val c=bitmap.getPixel(x,y);if(android.graphics.Color.red(c)<100&&android.graphics.Color.green(c)<100&&android.graphics.Color.blue(c)<100)count++}
            bitmap.recycle();return count
        }
        assertThat(ink("timer-weight-end")).isGreaterThan(ink("timer-weight-start"))
    }
}
