package com.polinalinen.madre.ui.razvorot

import androidx.compose.runtime.*

/** Instrumentation state only. The controls themselves live in src/debug. */
internal class PaperDebugState {
    var renderer by mutableStateOf("PENDING")
    var reason by mutableStateOf("awaiting-surface")
    var renderedFrames by mutableIntStateOf(0)
    var cachedPages by mutableIntStateOf(0)
    var grid by mutableStateOf(false)
    var fps by mutableFloatStateOf(0f)
    var duration by mutableFloatStateOf(0f)
}
internal val LocalPaperDebug = staticCompositionLocalOf { PaperDebugState() }

/** Match consumption to the EXACT stamped EGL buffer, including skipped frames. */
internal class PaperPresentation {
    private val queued=sortedMapOf<Long,Boolean>()
    private var last=0L
    @Synchronized fun queued(timestamp:Long,paper:Boolean) {
        queued[timestamp]=paper
        while(queued.size>128)queued.remove(queued.firstKey())
    }
    @Synchronized fun consumed(timestamp:Long):Boolean {
        if(timestamp<=last)return false
        last=timestamp
        val paper=queued[timestamp]==true
        queued.keys.toList().filter{it<=timestamp}.forEach{queued.remove(it)}
        return paper
    }
}
