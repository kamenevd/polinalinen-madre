package com.polinalinen.madre

import com.polinalinen.madre.ui.kit.*
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
import com.polinalinen.madre.ui.kit.Atmosphere
import com.polinalinen.madre.ui.razvorot.debugCurlFold
import com.polinalinen.madre.ui.razvorot.point
import com.polinalinen.madre.ui.kit.DawnPaper
import com.polinalinen.madre.ui.kit.DayPaper
import com.polinalinen.madre.ui.kit.DuskPaper
import com.polinalinen.madre.ui.kit.NightPaper
import com.polinalinen.madre.ui.razvorot.PaperLight
import com.polinalinen.madre.ui.razvorot.captureName
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
    private var compositeStableFrames=0
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
        val automation=InstrumentationRegistry.getInstrumentation().uiAutomation
        val roots=automation.windows.mapNotNull{it.root}+listOfNotNull(automation.rootInActiveWindow)
        roots.forEach {root->
            val notices=root.findAccessibilityNodeInfosByText("Viewing full screen")
            check(notices.none{it.isVisibleToUser && it.packageName?.toString()=="com.android.systemui"}){"System fullscreen confirmation is covering the actual screen"}
        }
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
            val paper=when(rule.activity.atmosphere){Atmosphere.DAY->DayPaper;Atmosphere.DUSK->DuskPaper;Atmosphere.NIGHT->NightPaper;else->DawnPaper}
            val expected=paper.bg.red*255f*.6f
            val tolerance=if(expected<30f)1f else 5f
            val deadline=android.os.SystemClock.uptimeMillis()+5_000L
            var previous=-1;var stable=0
            // A dialog owns a separate animation clock. Its first committed
            // buffer can still have scrimAlpha≈0.025, despite activity idle.
            // Await the actual compositor pixels, not an arbitrary sleep count.
            while(true) {
                bitmap=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                assertThat(bitmap.width).isEqualTo(412);assertThat(bitmap.height).isEqualTo(915)
                val observed=(80..280).map{android.graphics.Color.red(bitmap.getPixel(10,it))}.sorted()[100]
                val matches=kotlin.math.abs(observed-expected)<=tolerance
                stable=if(matches && observed==previous)stable+1 else if(matches)1 else 0
                previous=observed
                if(stable>=3){compositeStableFrames=stable;break}
                if(android.os.SystemClock.uptimeMillis()>=deadline) {
                    val failure=ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,failure)
                    TestStorage().openOutputFile("screenshots/$name-unsettled-composite.png").use{it.write(failure.toByteArray())}
                    error("Real modal composite did not reach40% scrim: expectedR=$expected observedR=$observed")
                }
                bitmap.recycle()
                rule.mainClock.advanceTimeByFrame();rule.waitForIdle()
                android.os.SystemClock.sleep(16L)
            }
        }
        assertThat(bitmap.width).isEqualTo(412)
        assertThat(bitmap.height).isEqualTo(915)
        val out=ByteArrayOutputStream();check(bitmap.compress(Bitmap.CompressFormat.PNG,100,out))
        val bytes=out.toByteArray();assertThat(bytes.size).isGreaterThan(1000)
        TestStorage().openOutputFile("screenshots/$name.png").use{it.write(bytes)}
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
            val highlight=fold.point(fold.px+PaperLight.HIGHLIGHT_ANGLE*fold.radius,fold.height/2).x
            val edge=fold.point(fold.px+kotlin.math.PI.toFloat()/2*fold.radius,fold.height/2).x.toInt()
            fun medianAt(x:Int)=(640..820).map{android.graphics.Color.red(bitmap.getPixel(x.coerceIn(0,411),it))}.sorted()[90]
            val inside=medianAt(edge-2);val outside=medianAt(edge+4)
            TestStorage().openOutputFile("screenshots/$name-pixel-profile.json").bufferedWriter().use{it.write("{\"renderer\":\"$renderer\",\"theme\":\"${rule.activity.atmosphere?.captureName() ?: "dawn"}\",\"width\":412,\"height\":915,\"projectedX\":$xs,\"medianR\":$medians,\"highlightTheta\":1.12,\"highlightProjectedX\":$highlight,\"silhouetteX\":$edge,\"edgeInsideR\":$inside,\"edgeOutsideR\":$outside}")}
            if(night)assertThat(inside-outside).isAtLeast(2)
        }
        bitmap.recycle()
        return MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it)}
    }
    @Test fun actualNavigationAndNativeSceneMatrix() {
        select("home-dawn");val toc=capture("01-table-of-contents")
        rule.onNodeWithTag("book-cover").assertDoesNotExist()
        rule.onNodeWithTag("toc-search").performTextReplacement("Чиабатта")
        rule.waitForIdle();val searched=capture("02-toc-search")
        assertThat(searched).isNotEqualTo(toc)
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
            val suffix=theme.captureName()
            listOf("curl-mid","curl-back","gles2-curl-mid","gles2-curl-back").forEach {scene->select(scene,theme);capture("$scene-$suffix")}
            select("reader-text-16",theme);capture("scrim-baseline-$suffix")
            val before=marginMedian
            select("reading-sheet",theme);capture("reading-sheet-$suffix")
            assertThat(marginMedian.toFloat()).isWithin(if(theme==Atmosphere.NIGHT)1f else 5f).of(before*.6f)
            assertThat(pageSpan).isGreaterThan(20) // real page glyphs beneath the translucent scrim
            TestStorage().openOutputFile("screenshots/reading-sheet-$suffix-composite.json").bufferedWriter().use{it.write("{\"capture\":\"actual-UiAutomation-system-composite\",\"theme\":\"$suffix\",\"beforeR\":$before,\"afterR\":$marginMedian,\"pageSpan\":$pageSpan,\"scrimAlpha\":0.4,\"stableFrames\":$compositeStableFrames}")}
        }
    }
}
