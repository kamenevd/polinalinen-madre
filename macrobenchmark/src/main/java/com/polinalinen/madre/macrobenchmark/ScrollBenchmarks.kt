package com.polinalinen.madre.macrobenchmark

import android.content.Intent
import androidx.benchmark.macro.*
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TARGET="com.polinalinen.madre.benchmark"
private val routes=listOf("toc","oven","chapter-benchmark-scroll")
private fun routeIntent(scene:String)=Intent().setClassName(TARGET,"com.polinalinen.madre.ui.razvorot.RazvorotSceneActivity")
    .putExtra("scene",scene).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
private fun MacrobenchmarkScope.scroll() {
    val x=device.displayWidth/2;val top=device.displayHeight/4;val bottom=device.displayHeight*3/4
    repeat(3){device.swipe(x,bottom,x,top,30);device.waitForIdle();device.swipe(x,top,x,bottom,30);device.waitForIdle()}
}
@RunWith(AndroidJUnit4::class)
class ScrollBenchmarks {
    @get:Rule val benchmark=MacrobenchmarkRule()
    private fun measure(route:String)=benchmark.measureRepeated(
        packageName=TARGET,metrics=listOf(FrameTimingMetric()),iterations=5,
        compilationMode=CompilationMode.None(),startupMode=StartupMode.WARM,
        setupBlock={startActivityAndWait(routeIntent(route));device.waitForIdle()},
        measureBlock={scroll()})
    @Test fun tocScroll()=measure(routes[0])
    @Test fun ovenScroll()=measure(routes[1])
    @Test fun chapterScroll()=measure(routes[2])
}
@RunWith(AndroidJUnit4::class)
class GenerateReadingProfile {
    @get:Rule val profile=BaselineProfileRule()
    @Test fun actualProductionReadingJourneys()=profile.collect(TARGET,includeInStartupProfile=true) {
        routes.forEach{route->startActivityAndWait(routeIntent(route));scroll()}
    }
}
