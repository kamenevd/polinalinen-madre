package com.polinalinen.madre.ui.razvorot

import android.app.ActivityManager
import android.content.Context
import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sin

/** Pure gesture model. A down never moves a page; cancel can never commit. */
data class PaperGesture(val page: Int, val count: Int, val width: Float, val dx: Float = 0f) {
    val direction: Int get() = if (dx < 0) 1 else -1
    val target: Int get() = (page + direction).coerceIn(0, (count - 1).coerceAtLeast(0))
    val progress: Float get() = if (target == page || width <= 0f) 0f else (abs(dx) / width).coerceIn(0f, 1f)
    fun release(cancelled: Boolean): Int = if (!cancelled && progress >= .34f) target else page
}
/** Cylinder cross-section: rounded bend, never a rigid Y-axis card rotation. */
object PaperGeometry {
    fun foldX(width: Float, progress: Float) = width * (1f - progress.coerceIn(0f, 1f))
    fun radius(width: Float, progress: Float) = width * (.09f + .07f * sin(progress.coerceIn(0f, 1f) * Math.PI).toFloat())
    fun canCurl(lowRam: Boolean, reduced: Boolean, renderer: Boolean, systemMotion: Boolean) = !lowRam && !reduced && renderer && systemMotion
}

@Composable
fun PaperTurn(page: Int, count: Int, preferences: ReadingPreferences, onPage: (Int) -> Unit, modifier: Modifier = Modifier, content: @Composable (Int) -> Unit) {
    val context = LocalContext.current
    val lowRam = remember { (context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice }
    val systemMotion = Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    val renderer = androidx.compose.ui.platform.LocalView.current.isHardwareAccelerated
    val curl = preferences.mode == ReadingMode.BOOK && PaperGeometry.canCurl(lowRam, preferences.reducedMotion, renderer, systemMotion)
    var width by remember { mutableFloatStateOf(1f) }
    var drag by remember(page) { mutableFloatStateOf(0f) }
    var gripY by remember { mutableFloatStateOf(.5f) }
    var settling by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val p = LocalPaper.current
    val gesture = PaperGesture(page, count, width, drag)
    fun settle(cancelled: Boolean) {
        if (settling) return
        val selected = gesture.release(cancelled)
        scope.launch {
            settling = true
            val animation = Animatable(drag)
            animation.animateTo(if (selected == page) 0f else if (drag < 0f) -width else width, tween(if (preferences.reducedMotion) 180 else 740)) { drag = value }
            drag = 0f
            onPage(selected)
            settling = false
        }
    }
    Box(modifier.fillMaxSize().semantics {
        stateDescription = "Страница ${page + 1} из $count"
        customActions = listOf(
            CustomAccessibilityAction("Следующая страница") { if (page + 1 < count) { onPage(page + 1); true } else false },
            CustomAccessibilityAction("Предыдущая страница") { if (page > 0) { onPage(page - 1); true } else false },
        )
    }.pointerInput(page, count, curl, settling) {
        detectDragGestures(
            onDragStart = { gripY = (it.y / size.height).coerceIn(0f, 1f); width = size.width.toFloat() },
            onDragCancel = { settle(true) },
            onDragEnd = { settle(false) },
        ) { change, amount ->
            if (!settling) { change.consume(); drag += amount.x }
        }
    }) {
        val progress = gesture.progress
        if (progress > 0f) Box(Modifier.fillMaxSize()) { content(gesture.target) }
        Box(Modifier.fillMaxSize().drawWithContent {
            width = size.width
            if (progress == 0f) drawContent()
            else if (!curl) translate(left = drag) { drawContent() }
            else {
                // Clip the front along a gently tilted fold line; the curved verso below
                // follows the finger. Reverse direction mirrors the same geometry.
                val fold = PaperGeometry.foldX(size.width, progress)
                if (drag < 0f) clipRect(right = fold) { drawContent() }
                else clipRect(left = size.width - fold) { drawContent() }
            }
        }) { content(page) }
        if (curl && progress > 0f) Canvas(Modifier.fillMaxSize()) {
            val reverse = drag > 0f
            val fold = PaperGeometry.foldX(size.width, progress)
            val radius = PaperGeometry.radius(size.width, progress)
            val tilt = (gripY - .5f) * size.width * .18f * sin(progress * Math.PI).toFloat()
            fun x(v: Float) = if (reverse) size.width - v else v
            val back = Path().apply {
                moveTo(x(fold), 0f)
                cubicTo(x(fold - radius * .7f), size.height * .25f, x(fold - radius + tilt), size.height * .65f, x(fold + tilt), size.height)
                lineTo(x(fold + radius + tilt), size.height)
                cubicTo(x(fold - radius * .1f + tilt), size.height * .65f, x(fold + radius * .2f), size.height * .25f, x(fold + radius), 0f)
                close()
            }
            val left = minOf(x(fold - radius), x(fold + radius))
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = .22f), Color.Transparent), left - radius * .4f, left + radius * 2.6f), Offset(left - radius * .4f, 0f), Size(radius * 3f, size.height))
            drawPath(back, Brush.horizontalGradient(listOf(p.bg2, p.bg, p.card, p.bg2), left, left + radius * 2f))
        }
    }
}
