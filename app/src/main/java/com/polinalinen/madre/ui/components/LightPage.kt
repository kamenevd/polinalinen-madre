package com.polinalinen.madre.ui.components

import android.content.Context
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.sp
import com.polinalinen.madre.ui.theme.Cocoa
import com.polinalinen.madre.ui.theme.Cream
import kotlin.math.abs
import kotlin.math.min

/**
 * DESIGN-V4.md Cycle 4, фича «Страница на просвет» (LightPage). Наклоняешь
 * телефон — страница ловит свет: блик по бумаге и водяной знак, видимый
 * только на просвет. Датчик — ROTATION_VECTOR; при его отсутствии фича
 * молчит полностью. Отображение наклона в alpha/смещение — чистые функции,
 * вынесены из Composable ради юнит-теста (тот же приём, что InkBlot/WornPage).
 */
object LightPage {
    const val ALPHA_MIN = 0.05f
    const val ALPHA_MAX = 0.08f

    /** Наклон, при котором блик доходит до края страницы и до максимальной alpha. */
    const val MAX_TILT_RAD = (Math.PI / 4).toFloat()

    data class SheenSpec(
        val alpha: Float,
        val cxFraction: Float,
        val cyFraction: Float,
    )

    /**
     * pitch/roll (радианы, из SensorManager.getOrientation) -> спека блика.
     * Alpha растёт с наклоном, но всегда в полосе 0.05..0.08 — просвет, а не
     * прожектор. Центр блика уезжает вслед за наклоном, не покидая страницы.
     */
    fun specFor(pitchRad: Float, rollRad: Float): SheenSpec {
        val tilt = min(1f, (abs(pitchRad) + abs(rollRad)) / MAX_TILT_RAD)
        return SheenSpec(
            alpha = ALPHA_MIN + (ALPHA_MAX - ALPHA_MIN) * tilt,
            cxFraction = 0.5f + (rollRad / MAX_TILT_RAD).coerceIn(-1f, 1f) * 0.5f,
            cyFraction = 0.5f - (pitchRad / MAX_TILT_RAD).coerceIn(-1f, 1f) * 0.5f,
        )
    }
}

/**
 * Вешается на viewport-контейнер любой страницы. [watermark] — текст водяного
 * знака (null — только блик); [mirrored] — знак отзеркален, как заголовок
 * следующей страницы, просвечивающий сквозь бумагу на развороте рецепта.
 * Без датчика ROTATION_VECTOR модификатор — no-op.
 */
fun Modifier.lightPage(watermark: String? = null, mirrored: Boolean = false): Modifier = composed {
    val context = LocalContext.current
    val sensorManager = remember { context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager }
    val sensor = remember(sensorManager) { sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) }
    if (sensorManager == null || sensor == null) return@composed Modifier // датчика нет — молчим

    var spec by remember { mutableStateOf<LightPage.SheenSpec?>(null) }
    DisposableEffect(sensorManager, sensor) {
        val listener = object : SensorEventListener {
            private val rotationMatrix = FloatArray(9)
            private val orientation = FloatArray(3)
            override fun onSensorChanged(event: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientation)
                val newSpec = LightPage.specFor(pitchRad = orientation[1], rollRad = orientation[2])
                val current = spec
                // Cycle 16: порог ~2° (0.035 рад) — микродрожание руки не двигает блик.
                if (current == null ||
                    abs(newSpec.cxFraction - current.cxFraction) > 0.02f ||
                    abs(newSpec.cyFraction - current.cyFraction) > 0.02f ||
                    abs(newSpec.alpha - current.alpha) > 0.005f
                ) {
                    spec = newSpec
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
        onDispose { sensorManager.unregisterListener(listener) }
    }

    val watermarkPaint = remember {
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
            textAlign = android.graphics.Paint.Align.CENTER
            color = Cocoa.toArgb()
        }
    }

    // Cycle 16: оригинальная логика отрисовки, throttle датчика выше.
    drawWithContent {
        drawContent()
        val s = spec ?: return@drawWithContent
        val center = Offset(size.width * s.cxFraction, size.height * s.cyFraction)
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Cream.copy(alpha = s.alpha), Color.Transparent),
                center = center,
                radius = size.maxDimension * 0.6f,
            ),
        )
        if (watermark != null) {
            watermarkPaint.textSize = 30.sp.toPx()
            watermarkPaint.alpha = (s.alpha * 255).toInt()
            val canvas = drawContext.canvas.nativeCanvas
            canvas.save()
            if (mirrored) canvas.scale(-1f, 1f, size.width / 2, size.height / 2)
            canvas.rotate(-14f, size.width / 2, size.height / 2)
            canvas.drawText(watermark, size.width / 2, size.height / 2, watermarkPaint)
            canvas.restore()
        }
    }
}
