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
    private fun select(scene:String) {
        rule.runOnIdle{rule.activity.scene=scene}
        rule.waitUntil(15_000){rule.onAllNodesWithTag("scene-$scene").fetchSemanticsNodes().isNotEmpty()}
        rule.waitForIdle()
        if(scene.startsWith("gles2-")) {
            rule.waitUntil(15_000) {
                val state=rule.onNodeWithTag("scene-$scene").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription).orEmpty()
                check(!state.startsWith("SLIDE:")){"Native GL fixture failed: $state"}
                state.startsWith("GLES2:") && (state.split(':').getOrNull(1)?.toIntOrNull()?:0)>0
            }
        }
        if(scene.startsWith("starter-")) {
            val phase=scene.substringAfter('-').uppercase()
            rule.onNodeWithTag("starter-phase-$phase").assertIsDisplayed()
            rule.onNodeWithTag("starter-jar").assertIsDisplayed()
            rule.onNodeWithTag("nav-2").assertIsSelected()
            rule.onNodeWithText("Покормить").assertIsDisplayed()
            rule.onAllNodesWithText("ИЗОЛИРОВАННАЯ DEBUG-СЦЕНА · БЕЗ СВЯЗИ").assertCountEquals(0)
            rule.onAllNodesWithText("Ответьте «да» или «нет»").assertCountEquals(0)
            rule.onAllNodesWithText("Алиса",substring=true).assertCountEquals(0)
        }
    }
    private fun capture(name:String):String {
        rule.waitForIdle()
        // Semantics/idle precede Surface presentation. Commit + PixelCopy fences the
        // actual current Window buffer, not a stale SurfaceFlinger screenshot.
        val committed=CountDownLatch(1)
        lateinit var bitmap:Bitmap
        rule.runOnIdle {
            val view=rule.activity.window.decorView
            check(view.isHardwareAccelerated)
            bitmap=Bitmap.createBitmap(view.width,view.height,Bitmap.Config.ARGB_8888)
            view.viewTreeObserver.registerFrameCommitCallback { committed.countDown() }
            view.invalidate()
        }
        check(committed.await(5,TimeUnit.SECONDS)){"No native frame committed for ${rule.activity.scene}"}
        val copied=CountDownLatch(1);var result=-1
        rule.runOnIdle {
            PixelCopy.request(rule.activity.window,bitmap,{status->result=status;copied.countDown()},Handler(Looper.getMainLooper()))
        }
        check(copied.await(5,TimeUnit.SECONDS)){"PixelCopy callback missing: $name"}
        check(result==PixelCopy.SUCCESS){"PixelCopy $name failed: $result"}
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
}
