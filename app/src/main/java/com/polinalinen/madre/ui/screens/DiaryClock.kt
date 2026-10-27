package com.polinalinen.madre.ui.screens

import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Presentation time only; the caller's phase/profile remain authoritative. */
@Composable
internal fun rememberDiaryNowMillis(overrideMillis: Long?, nowProvider: () -> Long): Long {
    val owner = LocalLifecycleOwner.current
    val currentProvider by rememberUpdatedState(nowProvider)
    var now by remember(overrideMillis, owner) { mutableStateOf(overrideMillis ?: nowProvider()) }
    DisposableEffect(overrideMillis, owner) {
        if (overrideMillis != null) {
            onDispose { }
        } else {
            val handler = Handler(Looper.getMainLooper())
            var disposed = false
            val tick = object : Runnable {
                override fun run() {
                    if (!disposed && owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                        now = currentProvider()
                        handler.postDelayed(this, 60_000L)
                    }
                }
            }
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START, Lifecycle.Event.ON_RESUME -> {
                        handler.removeCallbacks(tick)
                        tick.run()
                    }
                    Lifecycle.Event.ON_STOP, Lifecycle.Event.ON_DESTROY -> handler.removeCallbacks(tick)
                    else -> Unit
                }
            }
            // addObserver synchronizes an already-started owner through START/RESUME.
            owner.lifecycle.addObserver(observer)
            onDispose {
                disposed = true
                handler.removeCallbacks(tick)
                owner.lifecycle.removeObserver(observer)
            }
        }
    }
    return overrideMillis ?: now
}
