package com.polinalinen.madre

import android.graphics.Bitmap
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
    }
    private fun capture(name:String):String {
        rule.waitForIdle()
        val bitmap=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
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
        RazvorotScenes.forEach {scene->select(scene);capture(scene)}
    }
}
