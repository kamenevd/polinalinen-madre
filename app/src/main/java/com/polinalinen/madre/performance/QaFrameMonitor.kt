package com.polinalinen.madre.performance

import android.view.Window
import androidx.compose.runtime.*
import androidx.metrics.performance.JankStats

internal data class QaFrameSnapshot(val frames:Int=0,val fps:Double?=null,val p95UiMillis:Double=0.0,val jankPercent:Double=0.0)
/** Primitive bounded buffer: never retain reused FrameData or allocate/sort in its callback. */
internal class QaFrameAccumulator {
    private val durations=LongArray(600)
    private var count=0;private var jank=0;private var first=0L;private var last=0L
    @Synchronized fun add(start:Long,duration:Long,isJank:Boolean) {
        if(count==0)first=start
        last=start;durations[count%durations.size]=duration;count++;if(isJank)jank++
    }
    @Synchronized fun sample():QaFrameSnapshot {
        val n=count
        if(n==0)return QaFrameSnapshot()
        val sorted=durations.copyOf(minOf(n,durations.size)).sortedArray()
        val fps=if(n>=3 && last>first)(n-1)*1_000_000_000.0/(last-first) else null
        val result=QaFrameSnapshot(n,fps,sorted[(kotlin.math.ceil(sorted.size*.95).toInt()-1).coerceAtLeast(0)]/1_000_000.0,jank*100.0/n)
        count=0;jank=0;first=0;last=0
        return result
    }
}
internal class QaFrameMonitor(private val window:Window) {
    var enabled by mutableStateOf(false)
        private set
    private var resumed=false
    private var tracker:JankStats?=null
    private val accumulator=QaFrameAccumulator()
    fun chooseEnabled(value:Boolean) {
        enabled=value
        if(value && tracker==null)tracker=JankStats.createAndTrack(window){data->
            accumulator.add(data.frameStartNanos,data.frameDurationUiNanos,data.isJank)
        }
        tracker?.isTrackingEnabled=value && resumed
        if(!value)accumulator.sample()
    }
    fun resume(){resumed=true;tracker?.isTrackingEnabled=enabled}
    fun pause(){resumed=false;tracker?.isTrackingEnabled=false;accumulator.sample()}
    fun close(){pause();tracker=null}
    fun sample()=accumulator.sample()
}
internal val LocalQaFrameMonitor=staticCompositionLocalOf<QaFrameMonitor?>{null}
