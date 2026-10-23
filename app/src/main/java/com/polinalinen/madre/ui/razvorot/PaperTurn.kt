package com.polinalinen.madre.ui.razvorot

import android.content.Context
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.*
import androidx.compose.ui.viewinterop.AndroidView
import com.polinalinen.madre.ui.theme.LocalCalmMode
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlin.math.*

internal class PaperMotion(val direction: Int, val x0: Float, val y0: Float, var fold: PaperFold) {
    var target = fold
    var settling = false
}

@Composable fun PaperTurn(
    page: Int, count: Int, preferences: ReadingPreferences, onPage: (Int) -> Unit,
    modifier: Modifier = Modifier, onCenter: () -> Unit = {}, content: @Composable (Int) -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val view = LocalView.current
    val calm = LocalCalmMode.current
    val systemMotion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    var rendererFailed by remember { mutableStateOf(false) }
    var failureReason by remember { mutableStateOf("") }
    var slowSession by remember { mutableStateOf(false) }
    val curl = preferences.mode == ReadingMode.BOOK && !preferences.reducedMotion && !calm && systemMotion && !power.isPowerSaveMode && view.isHardwareAccelerated && !rendererFailed && !slowSession
    var width by remember { mutableFloatStateOf(360f) }
    var height by remember { mutableFloatStateOf(780f) }
    var motion by remember(page) { mutableStateOf<PaperMotion?>(null) }
    var fold by remember(page) { mutableStateOf<PaperFold?>(null) }
    var glView by remember { mutableStateOf<PaperGlView?>(null) }
    val textures = remember { mutableStateMapOf<Int, android.graphics.Bitmap>() }
    val layers = remember { mutableMapOf<Int, androidx.compose.ui.graphics.layer.GraphicsLayer>() }
    val p = LocalPaper.current
    val scope = rememberCoroutineScope()
    val latestPage by rememberUpdatedState(onPage)
    val latestCenter by rememberUpdatedState(onCenter)
    val latestCurl by rememberUpdatedState(curl)
    val latestMotion by rememberUpdatedState(motion)
    val debug = LocalPaperDebug.current
    val frameRate = remember { PaperFrameRate() }
    SideEffect {
        debug.cachedPages=textures.size
        if (!curl) {
            debug.renderer="SLIDE"
            debug.reason=when {
                preferences.mode!=ReadingMode.BOOK -> "preference-${preferences.mode}"
                preferences.reducedMotion -> "reduced-motion"
                calm -> "calm-mode"
                !systemMotion -> "system-animation-scale-zero"
                power.isPowerSaveMode -> "power-save"
                !view.isHardwareAccelerated -> "view-not-hardware-accelerated"
                rendererFailed -> failureReason
                slowSession -> "low-fps"
                else -> "unknown"
            }
            android.util.Log.i("MadrePaperGL", "SLIDE: ${debug.reason}")
        }
    }
    DisposableEffect(view,density,height) {
        val previous=if(android.os.Build.VERSION.SDK_INT>=29)view.systemGestureExclusionRects.toList() else emptyList()
        if(android.os.Build.VERSION.SDK_INT>=29) {
            val center=(view.height.takeIf{it>0}?: (height*density).roundToInt())/2
            val half=(100f*density).roundToInt()
            view.systemGestureExclusionRects=listOf(android.graphics.Rect(0,(center-half).coerceAtLeast(0),(24f*density).roundToInt(),center+half))
        }
        onDispose{if(android.os.Build.VERSION.SDK_INT>=29)view.systemGestureExclusionRects=previous}
    }

    // The next two pages in BOTH directions are laid out and captured before a gesture.
    // Theme/size/content changes invalidate these real Compose textures.
    val cacheKey = listOf(width, height, p, preferences.textSize, preferences.portions, count)
    LaunchedEffect(cacheKey) { textures.clear() }
    LaunchedEffect(motion) {
        var last = 0L
        while (motion != null && motion?.settling == false) {
            withFrameNanos { now ->
                val dt = if (last == 0L) 16f else ((now - last) / 1_000_000f).coerceIn(1f, 64f)
                last = now
                frameRate.frame(now/1_000_000L,dt);if(frameRate.disabled)slowSession=true
                val m = motion ?: return@withFrameNanos
                val a = 1f - exp(-dt / Curl.LAG_MS)
                val length = m.fold.length + (m.target.length - m.fold.length) * a
                val angle = m.fold.phi + (m.target.phi - m.fold.phi) * a
                m.fold = PaperFold.attached(width, height, m.y0, length, angle)
                fold = m.fold
            }
        }
    }
    fun settle(cancelled: Boolean, vx: Float, tap: Boolean = false) {
        val m = motion ?: return
        if (m.settling) return
        m.settling = true
        val releaseV=(if(!tap&&m.direction<0)2f*vx else vx).coerceIn(-Curl.V_MAX,Curl.V_MAX)
        val progress = if (m.direction > 0) m.fold.length / (2f * width) else 1f - m.fold.length / (2f * width)
        val complete = !cancelled && (tap || Curl.complete(m.direction, progress, releaseV))
        val fast = abs(releaseV) > 1.2f
        val targetTaper=((2f*width-m.target.length)/(1.2f*width)).coerceIn(0f,1f)
        val baseDegrees=if(targetTaper>.001f)m.target.phi/targetTaper*180f/PI.toFloat() else 0f
        scope.launch {
            val started = withFrameNanos { it }
            var last = started
            val flip = complete == (m.direction > 0)
            val spring = PaperSpring(width - m.fold.length, releaseV * 1000f,
                if (flip) -width - 150f else width,
                if (!latestCurl) 60f else if (!complete) Curl.K_CANCEL else if (fast) Curl.K_FAST else Curl.K_DONE,
                if (!complete && latestCurl) Curl.Z_CANCEL else Curl.Z_DONE)
            val min = if (!latestCurl) 250f else if (tap) 500f else if (fast) 360f else if (complete) 380f else 400f
            val max = if (!latestCurl) 350f else if (tap) 650f else if (fast) 450f else if (complete) 500f else 520f
            var done = false
            while (!done) withFrameNanos { now ->
                val dt = ((now - last) / 1_000_000f).coerceIn(1f, 64f); last = now
                frameRate.frame(now / 1_000_000L, dt); if (frameRate.disabled) slowSession = true
                spring.step(dt)
                spring.x = spring.x.coerceAtMost(width)
                val elapsed = (now - started) / 1_000_000f
                // Only the bounded tail is smoothly pushed home; the trajectory is a spring.
                if (elapsed > max - 60f) spring.x += (spring.target - spring.x) * ((elapsed - max + 60f) / 60f).coerceIn(0f, 1f)
                val length = (width - spring.x).coerceIn(0f, 2f * width + 150f)
                m.fold = PaperFold.anchored(width, height, m.y0, length, baseDegrees)
                fold = m.fold
                val arrived = if (flip) spring.x <= -width - 30f else abs(spring.x - width) < 2.5f && abs(spring.velocity) < 40f
                done = elapsed >= max || elapsed >= min && arrived
                debug.fps = 1000f / dt; debug.duration = elapsed
            }
            if (complete) {
                latestPage((page + m.direction).coerceIn(0, count - 1))
                if (preferences.haptics) view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
            }
            glView?.submit(null); motion = null; fold = null
        }
    }
    val motionNow = motion
    val turning = if (motionNow?.direction == -1) page - 1 else page
    val under = if (motionNow?.direction == 1) page + 1 else page
    val frontBitmap = textures[turning]
    val underBitmap = textures[under]
    val glFrame = if (curl && fold != null && frontBitmap != null && underBitmap != null) PaperGlFrame(fold!!, frontBitmap, underBitmap, p.bg.toArgb(), p == NightPaper, debug.grid) else null

    Box(modifier.fillMaxSize().onSizeChanged { width = it.width / density; height = it.height / density }.semantics(mergeDescendants=true) {
        stateDescription = "Страница ${page + 1} из $count"
        customActions = listOf(
            CustomAccessibilityAction("Следующая страница") { if (page + 1 < count) { latestPage(page + 1); true } else false },
            CustomAccessibilityAction("Предыдущая страница") { if (page > 0) { latestPage(page - 1); true } else false },
        )
    }.pointerInput(page, count, width, height) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            if (latestMotion != null) return@awaitEachGesture
            val start = down.position / density
            val tracker = VelocityTracker(); tracker.addPosition(down.uptimeMillis, down.position)
            var started = false; var ignored = false; var released = false
            try {
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    val pos = change.position / density; val delta = pos - start
                    tracker.addPosition(change.uptimeMillis, change.position)
                    if (!started && !ignored && delta.getDistance() >= 8f) {
                        val direction = Curl.direction(start.x, delta.x, delta.y, width)
                        if (direction == 0 || page + direction !in 0 until count) ignored = true
                        else {
                            val g = PaperFold.fromFinger(width, height, direction, start.x, start.y, pos.x, pos.y)
                            motion = PaperMotion(direction, start.x, start.y, g); fold = g; started = true
                        }
                    }
                    if (started) { motion?.target = PaperFold.fromFinger(width, height, motion!!.direction, start.x, start.y, pos.x, pos.y); change.consume() }
                    if (!change.pressed) {
                        released = true
                        if (started) settle(false, tracker.calculateVelocity().x / density / 1000f)
                        else if (!ignored && change.uptimeMillis - down.uptimeMillis < 350L) {
                            val dir = if (start.x >= width * .72f) 1 else if (start.x <= width * .28f) -1 else 0
                            if (dir == 0) latestCenter() else if (page + dir in 0 until count) {
                                val g = PaperFold.anchored(width, height, start.y, if (dir > 0) 24f else 2f * width - 30f, 0f)
                                motion = PaperMotion(dir, start.x, start.y, g); fold = g; settle(false, -dir * .7f, true)
                            }
                        }
                        break
                    }
                }
            } finally { if (started && !released) settle(true, 0f) }
        }
    }) {
        for (index in max(0, page - 2)..min(count - 1, page + 2)) key(index, cacheKey) {
            val layer = rememberGraphicsLayer()
            val recorded = remember(layer) { CompletableDeferred<Unit>() }
            DisposableEffect(layer, index) { layers[index] = layer; onDispose { if(layers[index] === layer)layers.remove(index) } }
            LaunchedEffect(layer, index, cacheKey) {
                // A Compose frame-clock tick is NOT an Android draw (especially in
                // instrumentation). Capture only after a real non-empty record.
                recorded.await()
                runCatching {
                    val bitmap=layer.toImageBitmap().asAndroidBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888,false)
                    if(bitmap!=null && density>2f) android.graphics.Bitmap.createScaledBitmap(bitmap,(width*2f).roundToInt().coerceAtLeast(1),(height*2f).roundToInt().coerceAtLeast(1),true).also{if(it!==bitmap)bitmap.recycle()} else bitmap
                }
                    .onSuccess { if (it != null) textures[index] = it }.onFailure {
                        if(it is CancellationException)throw it
                        failureReason="texture-capture-${it.javaClass.simpleName}: ${it.message}"
                        android.util.Log.e("MadrePaperGL",failureReason,it); rendererFailed = true
                    }
                textures.keys.toList().filter { abs(it - page) > 2 }.forEach { textures.remove(it) }
            }
            Box(Modifier.fillMaxSize().then(if(index!=page)Modifier.clearAndSetSemantics{} else Modifier).drawWithContent {
                val source = this
                layer.record { source.drawContent() }
                if(size.width>0f && size.height>0f)recorded.complete(Unit)
            }) { content(index) }
        }
        Canvas(Modifier.fillMaxSize()) {
            if(glFrame==null) {
                if(motionNow==null)layers[page]?.let{drawLayer(it)}
                else {
                    layers[under]?.let{drawLayer(it)}
                    val offset=-(fold?.length?:0f).coerceIn(0f,2f*width)/2f*density
                    layers[turning]?.let{layer->translate(left=offset){drawLayer(layer)}}
                    drawRect(Brush.horizontalGradient(listOf(Color.Transparent,Color.Black.copy(alpha=.25f),Color.Transparent),size.width+offset-30f*density,size.width+offset+30f*density))
                }
            }
        }
        if (curl) AndroidView(factory = { PaperGlView(it,failure={reason->failureReason=reason;rendererFailed=true;debug.renderer="SLIDE";debug.reason=reason},ready={debug.renderer="GLES2";debug.reason="egl-shaders-ready"},presented={debug.renderedFrames++}).also { glView = it } },
            modifier = Modifier.fillMaxSize(), update = { it.prefetch(textures.values.toList()); it.submit(glFrame) })
    }
}
