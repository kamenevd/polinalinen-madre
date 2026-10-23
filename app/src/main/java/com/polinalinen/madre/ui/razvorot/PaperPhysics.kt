package com.polinalinen.madre.ui.razvorot

import kotlin.math.*

/** PAGE-CURL v3. Coordinates and velocities are dp and dp/ms, never screen px. */
internal object Curl {
    var R_MAX = 78f
    var R_MIN = 38f
    const val R_SHRINK = .06f
    var LAG_MS = 38f
    var K_DONE = 36f
    var K_FAST = 68f
    var K_CANCEL = 95f
    var Z_DONE = 1f
    var Z_CANCEL = .8f
    const val EDGE = .19f
    const val V_MAX = 1.5f
    const val V_FLICK = .5f
    const val V_KEEP = .12f
    const val P_DONE = .5f
    fun zone(y: Float, height: Float) = when {
        y < height * .3f -> -22f
        y > height * .7f -> 22f
        else -> 0f
    }
    fun direction(x: Float, dx: Float, dy: Float, width: Float): Int = when {
        hypot(dx, dy) < 8f || abs(dx) < .5f * abs(dy) -> 0
        dx < 0f && x >= width * (1f - EDGE) -> 1
        dx > 0f && x >= 24f && x <= width * EDGE -> -1
        else -> 0
    }
    fun complete(direction: Int, progress: Float, vx: Float): Boolean {
        val along = -direction * vx.coerceIn(-V_MAX, V_MAX)
        return along > V_FLICK || (progress > P_DONE && along > -V_KEEP)
    }
}

internal data class PaperFold(val width: Float, val height: Float, val grip: Float, val length: Float, val phi: Float) {
    val radius = max(Curl.R_MIN, Curl.R_MAX - Curl.R_SHRINK * length)
    val nx = cos(phi)
    val ny = sin(phi)
    val distance: Float = run {
        val q = length / radius
        if (q >= PI) (length + PI.toFloat() * radius) / 2f else {
            // Monotone bisection is stable for a nearly flat sheet as well.
            var lo = 0f; var hi = PI.toFloat()
            repeat(24) { val mid = (lo + hi) / 2f; if (mid - sin(mid) < q) lo = mid else hi = mid }
            radius * (lo + hi) / 2f
        }
    }
    val px = width - nx * distance
    val py = grip - ny * distance
    fun d(x: Float, y: Float) = (x - px) * nx + (y - py) * ny
    fun spineAttached() = d(0f, 0f) <= .001f && d(0f, height) <= .001f
    companion object {
        fun fromFinger(width: Float, height: Float, direction: Int, x0: Float, y0: Float, x: Float, y: Float): PaperFold {
            val s = max(0f, if (direction > 0) x0 - x else x - x0)
            val length = if (direction > 0) s.coerceIn(0f, 2f * width - 10f) else (2f * width - 2f * x).coerceIn(0f, 2f * width)
            val delta = (-atan2(y - y0, max(60f, s)) * .6f * 180f / PI.toFloat()).coerceIn(-15f, 15f)
            return anchored(width, height, y0, length, Curl.zone(y0, height) + delta)
        }
        fun anchored(width: Float, height: Float, grip: Float, length: Float, degrees: Float): PaperFold {
            var phi = degrees * PI.toFloat() / 180f * ((2f * width - length) / (1.2f * width)).coerceIn(0f, 1f)
            val y = grip.coerceIn(8f, max(8f, height - 8f))
            repeat(8) {
                val fold = PaperFold(width, height, y, length, phi)
                if (fold.spineAttached()) return fold
                phi *= .7f
            }
            return PaperFold(width, height, y, length, 0f)
        }
    }
}

/** Damped physical spring, substepped to 8ms. No fixed duration tween. */
internal class PaperSpring(
    var x: Float, var velocity: Float, val target: Float,
    val stiffness: Float, val damping: Float,
) {
    fun step(milliseconds: Float) {
        val n = ceil(milliseconds / 8f).toInt().coerceAtLeast(1)
        val dt = milliseconds / n / 1000f
        repeat(n) {
            velocity += (stiffness * (target - x) - 2f * sqrt(stiffness) * damping * velocity) * dt
            x += velocity * dt
        }
    }
}

internal class PaperFrameRate {
    private var since = 0L
    private var last = 0L
    var disabled = false; private set
    fun frame(now: Long, frameMillis: Float) {
        if(last!=0L && now-last>100L)since=0L
        last=now
        if (frameMillis > 25f) {
            if (since == 0L) since = now
            if (now - since >= 2_000L) disabled = true
        } else since = 0L
    }
}
