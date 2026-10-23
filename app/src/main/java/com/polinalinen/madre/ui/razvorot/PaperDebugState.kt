package com.polinalinen.madre.ui.razvorot

import androidx.compose.runtime.*

/** Instrumentation state only. The controls themselves live in src/debug. */
internal class PaperDebugState {
    var grid by mutableStateOf(false)
    var fps by mutableFloatStateOf(0f)
    var duration by mutableFloatStateOf(0f)
}
internal val LocalPaperDebug = staticCompositionLocalOf { PaperDebugState() }
