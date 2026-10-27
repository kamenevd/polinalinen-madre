package com.polinalinen.madre.ui.kit

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.Window
import android.view.WindowManager
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.util.WeakHashMap

interface ImmersiveHost { val immersiveController: ImmersiveController }

/** One owner for the activity AND its modal windows. No screen writes window flags. */
class ImmersiveController {
    private val requests = linkedMapOf<Any, Boolean>()
    private val windows = WeakHashMap<Window, Int>()
    private val tones = linkedMapOf<Any, Boolean>()
    private var themeLight = true
    var paperColor:Int=android.graphics.Color.TRANSPARENT
        private set
    fun setPaperColor(color:Int) {if(paperColor!=color){paperColor=color;reapplyAll()}}
    var lightPaper: Boolean = true
        private set
    val wanted: Boolean get() = requests.values.any { it }

    fun request(key: Any, enabled: Boolean) { requests[key] = enabled; reapplyAll() }
    fun release(key: Any) { requests.remove(key); reapplyAll() }
    fun setPaperLight(light: Boolean) { themeLight = light; applyTone() }
    fun requestTone(key: Any, light: Boolean) { tones[key] = light; applyTone() }
    fun releaseTone(key: Any) { tones.remove(key); applyTone() }
    private fun applyTone() {
        val light = tones.values.lastOrNull() ?: themeLight
        if (lightPaper != light) { lightPaper = light; reapplyAll() }
    }
    fun attach(window: Window) {
        windows[window] = (windows[window] ?: 0) + 1
        configure(window)
        reapply(window)
    }
    fun detach(window: Window) {
        val count = windows[window] ?: return
        if (count <= 1) windows.remove(window) else windows[window] = count - 1
    }
    @Suppress("DEPRECATION")
    fun configure(window: Window) {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) {
            window.isNavigationBarContrastEnforced = false
            window.isStatusBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30)
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }
    @Suppress("DEPRECATION")
    fun reapply(window: Window) {
        // Android35 enforces transparent edge-to-edge; older OEMs need an explicit paper bar.
        window.navigationBarColor=if(Build.VERSION.SDK_INT>=35)android.graphics.Color.TRANSPARENT else paperColor
        WindowCompat.getInsetsController(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            isAppearanceLightStatusBars = lightPaper
            isAppearanceLightNavigationBars = lightPaper
            if (wanted) hide(WindowInsetsCompat.Type.systemBars()) else show(WindowInsetsCompat.Type.systemBars())
        }
    }
    fun reapplyAll() { windows.keys.toList().forEach(::reapply) }
}

val LocalImmersive = staticCompositionLocalOf<ImmersiveController?> { null }

fun Context.findActivity(): Activity? {
    var current: Context = this
    val seen = mutableSetOf<Context>()
    while (seen.add(current)) {
        if (current is Activity) return current
        current = (current as? ContextWrapper)?.baseContext ?: return null
    }
    return null
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun ProvidePaperWindows(light: Boolean, content: @Composable () -> Unit) {
    val activity = LocalContext.current.findActivity()
    val inherited = LocalImmersive.current
    val fallback = remember { ImmersiveController() }
    val controller = inherited ?: (activity as? ImmersiveHost)?.immersiveController ?: fallback
    val ime = WindowInsets.isImeVisible
    val color=LocalPaper.current.bg.toArgb()
    var previousIme by remember { mutableStateOf(ime) }
    SideEffect {
        if(controller.paperColor!=color) {
            controller.setPaperColor(color)
            activity?.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(color))
        }
        controller.setPaperLight(light)
        if (previousIme && !ime) controller.reapplyAll()
        previousIme = ime
    }
    DisposableEffect(controller, activity) {
        activity?.window?.let(controller::attach)
        onDispose { activity?.window?.let(controller::detach) }
    }
    CompositionLocalProvider(LocalImmersive provides controller, content = content)
}

/** The key belongs to the screen composition; recreation restores it from screen state. */
@Composable fun Immersive(enabled: Boolean = true, barsRevealed: Boolean = false, keepScreenOn: Boolean = false) {
    val controller = LocalImmersive.current
    val key = remember { Any() }
    val view = LocalView.current
    DisposableEffect(controller, enabled, barsRevealed) {
        controller?.request(key, enabled && !barsRevealed)
        onDispose { controller?.release(key) }
    }
    DisposableEffect(view, keepScreenOn) {
        KeepScreenRequests.request(view, key, keepScreenOn)
        onDispose { KeepScreenRequests.release(view, key) }
    }
}

/** Reader/timer/overlay share a View: out-of-order disposal must not restore another owner's old true flag. */
internal object KeepScreenRequests {
    private class Leases(val baseline: Boolean, val requests: MutableMap<Any, Boolean> = linkedMapOf())
    private val views = WeakHashMap<android.view.View, Leases>()
    @Synchronized fun request(view: android.view.View, key: Any, enabled: Boolean) {
        val leases = views.getOrPut(view) { Leases(view.keepScreenOn) }
        leases.requests[key] = enabled
        view.keepScreenOn = leases.baseline || leases.requests.values.any { it }
    }
    @Synchronized fun release(view: android.view.View, key: Any) {
        val leases = views[view] ?: return
        leases.requests.remove(key)
        view.keepScreenOn = leases.baseline || leases.requests.values.any { it }
        if (leases.requests.isEmpty()) views.remove(view)
    }
}

/** Binds the actual DialogWindow, never an unsafe Context-as-Activity cast. */
@Composable internal fun PaperModalWindow() {
    val controller = LocalImmersive.current
    val view = LocalView.current
    val activity = LocalContext.current.findActivity()
    val light = LocalPaper.current.bg.luminance() > .5f
    val toneKey = remember { Any() }
    val window = (view.parent as? DialogWindowProvider)?.window
        ?: (view.rootView.parent as? DialogWindowProvider)?.window
    DisposableEffect(controller, window, view, light) {
        window?.let { controller?.attach(it); controller?.requestTone(toneKey, light) }
        val observer = view.viewTreeObserver
        val listener = android.view.ViewTreeObserver.OnWindowFocusChangeListener { focused ->
            if (focused) window?.let { controller?.reapply(it) }
        }
        observer.addOnWindowFocusChangeListener(listener)
        onDispose {
            if (observer.isAlive) observer.removeOnWindowFocusChangeListener(listener)
            window?.let { controller?.detach(it); controller?.releaseTone(toneKey) }
            activity?.window?.let { controller?.reapply(it) }
        }
    }
}
