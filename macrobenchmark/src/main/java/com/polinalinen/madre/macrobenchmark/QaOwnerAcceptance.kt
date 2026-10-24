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
        dismissKnownLauncherAnr()
        context.startActivity(Intent().setClassName(target,"com.polinalinen.madre.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        assertTrue(device.wait(Until.hasObject(By.pkg(target)),20_000))
        dismissKnownLauncherAnr()
        device.wait(Until.findObject(By.text("ПОЗЖЕ")),5000)?.click()
        device.wait(Until.findObject(By.text("Читать")),3000)?.click()
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
        // Diagnostics off by default/on relaunch; this explicit test never changes global OS settings.
        File(output,"acceptance.txt").writeText("Actual qa variant; FLAG_DEBUGGABLE=false; full unmodified UiDevice frames. Android request is not physical Huawei vibration or smoothness acceptance.\n")
    }
}
