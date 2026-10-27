package com.polinalinen.madre.macrobenchmark

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.benchmark.macro.*
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.regex.Pattern

/** External driver for the unchanged issued15 -> same-run ordinary QA16 upgrade.
 * Debug15 vs R8 QA16 is an actual payload comparison, NOT an isolated renderer/AOT gain.
 * Navigation/selectors and synthetic legacy plans are shared with QaOwnerAcceptance.
 */
@RunWith(AndroidJUnit4::class)
class ExactQaTiming {
    @get:Rule val benchmark = MacrobenchmarkRule()
    private val target = "com.polinalinen.madre.qa"
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val output get() = File(instrumentation.context.getExternalFilesDir(null), "exact-qa-routes").apply { mkdirs() }

    private fun checkBinaryContext() {
        val arm = InstrumentationRegistry.getArguments().getString("madre.exactQaArm")
        check(arm == "before15" || arm == "after16") { "Explicit frozen arm required" }
        val info = instrumentation.context.packageManager.getPackageInfo(target, 0)
        val debug = info.applicationInfo!!.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        check(info.longVersionCode == if (arm == "before15") 51L else 52L)
        check(debug == (arm == "before15")) { "Never suppress an AFTER debuggable regression" }
        if (arm == "after16") check(info.applicationInfo!!.isProfileableByShell)
    }

    private fun MacrobenchmarkScope.guard(label: String) {
        check(device.currentPackageName == target) { "Actual QA lost foreground" }
        check(device.wait(Until.hasObject(By.textContains(label)), 10_000)) { "Real route absent: $label" }
        check(device.currentPackageName == target) { "System/app dialog invalidates sample" }
    }

    private fun MacrobenchmarkScope.navigate(oven: Boolean) {
        startActivityAndWait(Intent().setClassName(target, "com.polinalinen.madre.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        check(device.wait(Until.hasObject(By.pkg(target)), 20_000))
        device.wait(Until.findObject(By.text("ПОЗЖЕ")), 3000)?.click()
        val tab = device.wait(Until.findObject(By.text(Pattern.compile(if (oven) "(?iu)печь" else "(?iu)читать"))), 5000)
        checkNotNull(tab) { "Actual MainActivity navigation entry missing" }; tab.click()
        guard(if (oven) "Уже печём" else "Оглавление")
        if (oven) check(device.wait(Until.hasObject(By.text("Чиабатта")), 10_000)) { "Legacy fixture did not render" }
        device.waitForIdle(500)
        val route = if (oven) "oven" else "toc"
        device.dumpWindowHierarchy(File(output, "$route-${iteration}.xml"))
        check(device.takeScreenshot(File(output, "$route-${iteration}.png")))
    }

    private fun measureScroll(oven: Boolean) {
        checkBinaryContext()
        benchmark.measureRepeated(packageName = target, metrics = listOf(FrameTimingMetric()), iterations = 5,
            compilationMode = CompilationMode.None(), startupMode = StartupMode.WARM,
            setupBlock = { navigate(oven) }, measureBlock = {
                guard(if (oven) "Уже печём" else "Оглавление")
                val x = device.displayWidth / 2
                val top = device.displayHeight / 4
                val bottom = device.displayHeight * 3 / 4
                repeat(3) {
                    device.swipe(x, bottom, x, top, 30); device.waitForIdle(500)
                    device.swipe(x, top, x, bottom, 30); device.waitForIdle(500)
                }
                guard(if (oven) "Уже печём" else "Оглавление")
            })
    }

    @Test fun mainActivityFrames() {
        checkBinaryContext()
        benchmark.measureRepeated(packageName = target, metrics = listOf(FrameTimingMetric()), iterations = 5,
            compilationMode = CompilationMode.None(), startupMode = StartupMode.COLD,
            setupBlock = { navigate(false); pressHome(); killProcess() },
            measureBlock = {
                startActivityAndWait(Intent().setClassName(target, "com.polinalinen.madre.MainActivity")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
                guard("Оглавление")
            })
    }
    @Test fun ovenScroll() = measureScroll(true)
    @Test fun tocScroll() = measureScroll(false)
}
