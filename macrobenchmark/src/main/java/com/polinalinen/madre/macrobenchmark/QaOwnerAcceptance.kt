package com.polinalinen.madre.macrobenchmark

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/** External UIAutomator: actual non-debuggable QA; no fixture/seeder Activity or login. */
class QaOwnerAcceptance {
    @Test fun actualUnmodifiedDeliveryReadsLegacyAndAddsPlan() {
        val context=InstrumentationRegistry.getInstrumentation().context
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val target="com.polinalinen.madre.qa"
        val output=File(context.getExternalFilesDir(null),"huawei-p0").apply{mkdirs()}
        try {
            context.startActivity(Intent().setClassName(target,"com.polinalinen.madre.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            assertTrue(device.wait(Until.hasObject(By.pkg(target)),20_000))
            device.wait(Until.findObject(By.text("ПОЗЖЕ")),3000)?.click()
            device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?iu)печь"))),5000)?.click()
            assertTrue("Legacy JSON must render in ordinary R8 delivery",device.wait(Until.hasObject(By.text("Чиабатта")),10_000))
            fun findByScrolling(selector:androidx.test.uiautomator.BySelector):androidx.test.uiautomator.UiObject2 {
                repeat(14) {
                    device.findObject(selector)?.let{return it}
                    device.swipe(device.displayWidth/2,device.displayHeight*3/4,device.displayWidth/2,device.displayHeight/3,25)
                    device.waitForIdle(500)
                }
                error("Real delivery control not found: $selector")
            }
            assertNotNull(findByScrolling(By.text("Хлебушек домашний")))
            findByScrolling(By.text("Хлеб «Семейный»")).click()
            val schedule=device.wait(Until.findObject(By.text("Ко времени")),10_000)
            assertNotNull("Real start sheet missing",schedule);schedule!!.click()
            findByScrolling(By.textStartsWith("Запланировать")).click()
            assertTrue("Real plan save must close its sheet",device.wait(Until.gone(By.textStartsWith("Запланировать")),10_000))
            // showHome retains the existing LazyColumn position by design. Return
            // to its header instead of treating a correctly offscreen title as loss.
            repeat(5){device.swipe(device.displayWidth/2,device.displayHeight/3,device.displayWidth/2,device.displayHeight*3/4,25)}
            assertTrue(device.wait(Until.hasObject(By.textContains("Уже печём")),10_000))
            device.dumpWindowHierarchy(File(output,"qa-delivery-plans-written.xml"))
            assertTrue(device.takeScreenshot(File(output,"qa-delivery-plans-written.png")))
            device.executeShellCommand("am force-stop $target")
            context.startActivity(Intent().setClassName(target,"com.polinalinen.madre.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            assertTrue(device.wait(Until.hasObject(By.text("Чиабатта")),15_000))
            assertNotNull(findByScrolling(By.text("Хлебушек домашний")))
            assertNotNull(findByScrolling(By.text("Хлеб «Семейный»")))
            device.dumpWindowHierarchy(File(output,"qa-delivery-plans-restored.xml"))
            assertTrue(device.takeScreenshot(File(output,"qa-delivery-plans-restored.png")))
        } catch(failure:Throwable) {
            device.dumpWindowHierarchy(File(output,"qa-delivery-failure.xml"))
            device.takeScreenshot(File(output,"qa-delivery-failure.png"))
            throw failure
        } finally {
            device.executeShellCommand("mkdir -p /sdcard/Download/madre-qa-p0; cp -R ${output.absolutePath}/. /sdcard/Download/madre-qa-p0/")
        }
    }

    @Test fun actualR8SettingsProbeAndLocalFpsAreVisible() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.context
        val target="com.polinalinen.madre.qa"
        val info=context.packageManager.getApplicationInfo(target,0)
        assertEquals(0,info.flags and ApplicationInfo.FLAG_DEBUGGABLE)
        val device=UiDevice.getInstance(instrumentation)
        val output=File(context.getExternalFilesDir(null),"huawei-p0").apply{mkdirs()}
        fun dismissKnownLauncherAnr() {
            val close=device.findObject(By.res("android:id/aerr_close"))?:return
            if(device.hasObject(By.textContains("Pixel Launcher")) || device.hasObject(By.textContains("Nexus Launcher")))close.click()
            else fail("App/unknown ANR must not be hidden")
        }
        fun shot(name:String) {
            device.waitForIdle(1000);SystemClock.sleep(1000)
            device.dumpWindowHierarchy(File(output,"$name.xml"))
            assertTrue(device.takeScreenshot(File(output,"$name.png")))
            assertEquals("Only full actual QA window is accepted",target,device.currentPackageName)
        }
        try {
        dismissKnownLauncherAnr()
        context.startActivity(Intent().setClassName(target,"com.polinalinen.madre.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.pkg(target)),20_000))
        dismissKnownLauncherAnr()
        device.wait(Until.findObject(By.text("ПОЗЖЕ")),5000)?.click()
        device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?iu)читать"))),3000)?.click()
        val settings=device.wait(Until.findObject(By.desc("Настройки")),15_000)
        assertNotNull("Real settings entry missing",settings);settings!!.click()
        val search=device.wait(Until.findObject(By.clazz("android.widget.EditText")),10_000)
        assertNotNull(search);search!!.click();device.waitForIdle(500);search.text="Тактильный"
        val ime=device.executeShellCommand("dumpsys input_method")
        if(ime.contains("mInputShown=true") || ime.contains("mIsInputViewShown=true"))device.pressBack()
        device.waitForIdle(1000)
        val probe=device.wait(Until.findObject(By.text("Проверить")),10_000)
        assertNotNull("Actual haptic probe not visible",probe);probe!!.click()
        assertTrue(device.wait(Until.hasObject(By.textContains("hasVibrator:")),10_000))
        shot("qa-haptic-diagnostics")
        val diagnostics=device.findObject(By.text("Диагностика QA"));assertNotNull(diagnostics);diagnostics!!.click()
        val fps=device.wait(Until.findObject(By.desc("Показывать FPS")),5000)
        assertNotNull(fps);fps!!.click()
        assertTrue(device.wait(Until.hasObject(By.text("JankStats · текущее окно · 1 с")),10_000))
        repeat(3){device.swipe(device.displayWidth/2,device.displayHeight*3/4,device.displayWidth/2,device.displayHeight/2,20);device.waitForIdle(300)}
        shot("qa-fps-settings")
        File(output,"qa-fps-visible.txt").writeText(device.findObject(By.textContains("кадров · UI p95"))?.text?:"No frame sample label")
        device.findObject(By.desc("Показывать FPS"))?.click()
        repeat(3){device.swipe(device.displayWidth/2,device.displayHeight/4,device.displayWidth/2,device.displayHeight*3/4,30)}
        val themeSearch=device.wait(Until.findObject(By.clazz("android.widget.EditText")),5000)
        assertNotNull(themeSearch);themeSearch!!.text="Тема"
        val night=device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("(?iu)ночь"))),5000)
        assertNotNull("Actual night theme control missing",night);night!!.click()
        shot("qa-night-settings")
        device.pressBack()
        assertTrue(device.wait(Until.hasObject(By.text("Оглавление")),10_000))
        shot("qa-night-toc")
        // Diagnostics off by default/on relaunch; this explicit test never changes global OS settings.
        File(output,"acceptance.txt").writeText("Actual qa variant; FLAG_DEBUGGABLE=false; full unmodified UiDevice frames. Android request is not physical Huawei vibration or smoothness acceptance.\n")
        // Gradle uninstalls the TEST APK immediately after the run, removing its external files.
        // Export unchanged bytes with the CI shell BEFORE uninstall; never crop/re-render them.
        device.executeShellCommand("mkdir -p /sdcard/Download/madre-qa-p0")
        device.executeShellCommand("cp -R ${output.absolutePath}/. /sdcard/Download/madre-qa-p0/")
        val exported=device.executeShellCommand("ls /sdcard/Download/madre-qa-p0/")
        for(name in listOf("qa-haptic-diagnostics.png","qa-fps-settings.png","qa-night-settings.png","qa-night-toc.png"))
            assertTrue("Raw frame must survive test APK uninstall: $name",exported.contains(name))
        } catch(failure:Throwable) {
            device.dumpWindowHierarchy(File(output,"qa-owner-failure.xml"))
            device.takeScreenshot(File(output,"qa-owner-failure.png"))
            throw failure
        } finally {
            device.executeShellCommand("mkdir -p /sdcard/Download/madre-qa-p0; cp -R ${output.absolutePath}/. /sdcard/Download/madre-qa-p0/")
        }
    }
}
