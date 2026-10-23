package com.polinalinen.madre

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.services.storage.TestStorage
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.ui.razvorot.RazvorotSceneActivity
import com.polinalinen.madre.ui.razvorot.RazvorotScenes
import com.polinalinen.madre.ui.razvorot.Atmosphere
import com.polinalinen.madre.ui.razvorot.debugCurlFold
import com.polinalinen.madre.ui.razvorot.point
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** All fixtures are local assets. No Room writes, accounts, network or family device. */
@RunWith(AndroidJUnit4::class)
class MainScreensScreenshotTest {
    @get:Rule val rule=createAndroidComposeRule<RazvorotSceneActivity>()
    private var marginMedian=0
    private var pageSpan=0
    private fun select(scene:String,theme:Atmosphere?=null) {
        rule.runOnIdle{rule.activity.scene=scene;rule.activity.atmosphere=theme}
        rule.waitUntil(15_000){rule.onAllNodesWithTag("scene-$scene").fetchSemanticsNodes().isNotEmpty()}
        rule.waitForIdle()
        if(scene=="reading-sheet") {
            rule.onNodeWithText("Как читать").assertIsDisplayed()
            checkNotNull(rule.activity.captureWindow){"Settings dialog has no attested Window"}
        }
        if(scene.startsWith("gles2-")) {
            try { rule.waitUntil(15_000) {
                val state=rule.onNodeWithTag("scene-$scene").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription).orEmpty()
                check(!state.startsWith("SLIDE:")){"Native GL fixture failed: $state"}
                // First-consumer redraw must reach a genuinely consumed paper
                // buffer; PixelCopy also proves actual ink/paper below.
                state.startsWith("GLES2:") && (state.split(':').getOrNull(1)?.toIntOrNull()?:0)>0
            } } catch(error:ComposeTimeoutException) { throw AssertionError("Static GL presentation: ${rule.onNodeWithTag("scene-$scene").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription)}\n${rule.onRoot().printToString()}",error) }
        }
        if(scene.startsWith("starter-")) {
            val phase=scene.substringAfter('-').uppercase()
            rule.onNodeWithTag("starter-phase-$phase").assertIsDisplayed()
            rule.onNodeWithTag("starter-jar").assertIsDisplayed()
            rule.onNodeWithTag("nav-2").assertIsSelected()
            // The jar screen scrolls on a small viewport; actual MainActivity
            // navigation below exercises this action after scrolling into view.
            rule.onNodeWithText("Покормить").assertExists().assertHasClickAction()
            rule.onAllNodesWithText("ИЗОЛИРОВАННАЯ DEBUG-СЦЕНА · БЕЗ СВЯЗИ").assertCountEquals(0)
            rule.onAllNodesWithText("Ответьте «да» или «нет»").assertCountEquals(0)
            rule.onAllNodesWithText("Алиса",substring=true).assertCountEquals(0)
        }
    }
    private fun capture(name:String):String {
        rule.waitForIdle()
        // Semantics/idle precede Surface presentation. Commit + PixelCopy fences the
        // actual current Window buffer, not a stale SurfaceFlinger screenshot.
        lateinit var committed:CountDownLatch
        lateinit var bitmap:Bitmap
        lateinit var window:android.view.Window
        rule.runOnIdle {
            window=rule.activity.captureWindow?:rule.activity.window
            val view=window.decorView
            check(view.isHardwareAccelerated)
            bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            val windows=listOfNotNull(rule.activity.window,rule.activity.captureWindow).distinct()
            committed=CountDownLatch(windows.size)
            windows.forEach {owner->
                owner.decorView.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
                owner.decorView.invalidate()
            }
        }
        check(committed.await(5,TimeUnit.SECONDS)){"No native frame committed for ${rule.activity.scene}"}
        val copied=CountDownLatch(1);var result=-1
        rule.runOnIdle {
            PixelCopy.request(window,bitmap,{status->result=status;copied.countDown()},Handler(Looper.getMainLooper()))
        }
        check(copied.await(5,TimeUnit.SECONDS)){"PixelCopy callback missing: $name"}
        check(result==PixelCopy.SUCCESS){"PixelCopy $name failed: $result"}
        if(rule.activity.captureWindow!=null) {
            // PixelCopy of ONE translucent DialogWindow has no activity pixels.
            // Capture the actual compositor result; never invent a PNG composite.
            bitmap.recycle()
            bitmap=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        }
        assertThat(bitmap.width).isEqualTo(412)
        assertThat(bitmap.height).isEqualTo(915)
        marginMedian=(80..280).map{android.graphics.Color.red(bitmap.getPixel(10,it))}.sorted()[100]
        val band=(80..280 step 2).flatMap{y->(80..260 step 2).map{x->android.graphics.Color.red(bitmap.getPixel(x,y))}}
        pageSpan=band.max()-band.min()
        if(name.startsWith("gles2-curl-")) {
            // The GL-only scene holds its non-null fold until this PixelCopy.
            // Blank/transparent readiness or an all-black GL buffer cannot pass.
            var ink=0;var paper=0;var total=0
            for(y in bitmap.height/5 until bitmap.height*4/5)for(x in bitmap.width/10 until bitmap.width*9/10) {
                val color=bitmap.getPixel(x,y);val r=android.graphics.Color.red(color);val g=android.graphics.Color.green(color);val b=android.graphics.Color.blue(color)
                val night=rule.activity.atmosphere==Atmosphere.NIGHT
                if(if(night)r>60&&g>50&&b>40 else r<130&&g<130&&b<130)ink++
                if(if(night)r in 3..40 && g in 2..40 else r>180&&g>150&&b>120)paper++
                total++
            }
            assertThat(ink).isGreaterThan(100) // real printed text in the GL-only viewport
            assertThat(paper).isGreaterThan(total/3) // not a blank black texture
        }
        val scene=rule.activity.scene
        if(scene=="curl-mid" || scene=="curl-back" || scene.startsWith("gles2-curl-")) {
            val fold=debugCurlFold(412f,915f,scene)
            val xs=listOf(1.7f,1.9f,2.3f,2.7f,3.05f).map{theta->fold.point(fold.px+theta*fold.radius,fold.height/2).x.toInt().coerceIn(0,411)}
            val medians=xs.map{x->(640..820).map{android.graphics.Color.red(bitmap.getPixel(x,it))}.sorted()[90]}
            val night=rule.activity.atmosphere==Atmosphere.NIGHT
            assertThat(medians.max()-medians.min()).isAtLeast(if(night)1 else 10)
            val renderer=if(scene.startsWith("gles2-"))"REAL_GLES2" else "CPU24x48"
            TestStorage().openOutputFile("screenshots/$name-pixel-profile.json").bufferedWriter().use{it.write("{\"renderer\":\"$renderer\",\"theme\":\"${rule.activity.atmosphere}\",\"width\":412,\"height\":915,\"projectedX\":$xs,\"medianR\":$medians}")}
        }
        val out=ByteArrayOutputStream();check(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));bitmap.recycle()
        val bytes=out.toByteArray();assertThat(bytes.size).isGreaterThan(1000)
        TestStorage().openOutputFile("screenshots/$name.png").use{it.write(bytes)}
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    }
    @Test fun actualNavigationAndNativeSceneMatrix() {
        select("home-dawn");val home=capture("01-home")
        // The second shot is an actual click, navigation and idle, NOT another home shot.
        rule.onNodeWithTag("book-cover").performClick()
        rule.onNodeWithText("ОГЛАВЛЕНИЕ").assertIsDisplayed()
        rule.waitForIdle();val toc=capture("02-table-of-contents")
        assertThat(toc).isNotEqualTo(home)
        select("reader-title-dawn");capture("03-first-chapter")
        select("reader-title-last-night");capture("04-last-chapter-night")
        select("curl-top");capture("05-reader-curl")
        select("alice-listening");capture("06-alice-sheet")
        val hashes=mutableMapOf<String,String>()
        RazvorotScenes.forEach {scene->select(scene);hashes[scene]=capture(scene)}
        assertThat(listOf("starter-growing","starter-peak","starter-hungry").map{hashes[it]}.distinct()).hasSize(3)
        assertThat(hashes["reading-sheet"]).isNotEqualTo(hashes["reader-text-16"])
    }
    @Test fun gles2RendersActualNativePaperFrame() {
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        fun shell(command:String)=ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use{it.readText().trim()}
        val old=shell("settings get global animator_duration_scale").toFloatOrNull()?:0f
        try {
            shell("settings put global animator_duration_scale 1")
            select("reader-chrome")
            fun renderer()=rule.onNodeWithTag("scene-reader-chrome").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription).orEmpty()
            try {
                rule.waitUntil(15_000){renderer().startsWith("GLES2:") && (renderer().split(':').getOrNull(2)?.toIntOrNull()?:0)>=3}
            } catch (error: ComposeTimeoutException) {
                throw AssertionError("Real GLES2 readiness failed: ${renderer()}\n${rule.onRoot().printToString()}",error)
            }
            rule.onNodeWithTag("paper-turn").performTouchInput {
                swipe(androidx.compose.ui.geometry.Offset(width*.95f,height*.5f),androidx.compose.ui.geometry.Offset(width*.08f,height*.5f),150)
            }
            rule.waitUntil(15_000){(renderer().split(':').getOrNull(1)?.toIntOrNull()?:0)>0}
        } finally {shell("settings put global animator_duration_scale $old")}
    }
    @Test fun actualFourThemeCurlAndCompositedReadingSheet() {
        listOf(Atmosphere.DAWN,Atmosphere.DAY,Atmosphere.DUSK,Atmosphere.NIGHT).forEach {theme->
            val suffix=theme.name.lowercase()
            listOf("curl-mid","curl-back","gles2-curl-mid","gles2-curl-back").forEach {scene->select(scene,theme);capture("$scene-$suffix")}
            select("reader-text-16",theme);capture("scrim-baseline-$suffix")
            val before=marginMedian
            select("reading-sheet",theme);capture("reading-sheet-$suffix")
            assertThat(marginMedian.toFloat()).isWithin(5f).of(before*.6f)
            assertThat(pageSpan).isGreaterThan(20) // real page glyphs beneath the translucent scrim
            TestStorage().openOutputFile("screenshots/reading-sheet-$suffix-composite.json").bufferedWriter().use{it.write("{\"capture\":\"actual-UiAutomation-system-composite\",\"beforeR\":$before,\"afterR\":$marginMedian,\"pageSpan\":$pageSpan,\"scrimAlpha\":0.4}")}
        }
    }
}
