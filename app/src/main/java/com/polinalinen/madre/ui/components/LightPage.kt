package com.polinalinen.madre.ui.components

import android.content.Context
import android.graphics.Typeface
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.ObserverModifierNode
import androidx.compose.ui.node.observeReads
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.InspectorInfo
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

    /**
     * Порог, ниже которого новая спека считается «тем же самым бликом»
     * (Cycle 16). Микродрожание руки не должно двигать пятно света и уж тем
     * более пересобирать кисть градиента.
     */
    const val CENTER_EPSILON = 0.02f
    const val ALPHA_EPSILON = 0.005f

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

    /**
     * Сдвинулся ли блик настолько, чтобы это стоило перерисовки. Чистая
     * функция — ровно затем, чтобы порог проверялся тестом, а не наблюдением
     * за телефоном в руках.
     */
    fun isMeaningfulChange(current: SheenSpec?, next: SheenSpec): Boolean =
        current == null ||
            abs(next.cxFraction - current.cxFraction) > CENTER_EPSILON ||
            abs(next.cyFraction - current.cyFraction) > CENTER_EPSILON ||
            abs(next.alpha - current.alpha) > ALPHA_EPSILON
}

/**
 * Вешается на viewport-контейнер любой страницы. [watermark] — текст водяного
 * знака (null — только блик); [mirrored] — знак отзеркален, как заголовок
 * следующей страницы, просвечивающий сквозь бумагу на развороте рецепта.
 * Без датчика ROTATION_VECTOR модификатор — no-op.
 */
fun Modifier.lightPage(watermark: String? = null, mirrored: Boolean = false): Modifier =
    this then LightPageElement(watermark, mirrored)

/**
 * Cycle 16: собственный узел вместо composed {}. Здесь, в отличие от износа и
 * кофейных кругов, drawWithCache не помог бы: модификатору нужен не «посчитать
 * раз от размера», а живая подписка на датчик. В composed {} её держал
 * DisposableEffect — то есть подписка была привязана к КОМПОЗИЦИИ. У узла она
 * привязана к присоединению к дереву (onAttach/onDetach), что и есть настоящее
 * время жизни модификатора: экран ушёл — слушатель снят, и никаких «датчик
 * остался висеть, потому что композиция ещё не выбросила лямбду».
 */
private data class LightPageElement(
    private val watermark: String?,
    private val mirrored: Boolean,
) : ModifierNodeElement<LightPageNode>() {

    override fun create(): LightPageNode = LightPageNode(watermark, mirrored)

    override fun update(node: LightPageNode) {
        node.update(watermark, mirrored)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "lightPage"
        properties["watermark"] = watermark
        properties["mirrored"] = mirrored
    }
}

private class LightPageNode(
    private var watermark: String?,
    private var mirrored: Boolean,
) : Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode, ObserverModifierNode {

    private var sensorManager: SensorManager? = null
    private var sensor: Sensor? = null
    private var spec: LightPage.SheenSpec? = null

    // Кисть блика живёт между кадрами и пересобирается только когда блик правда
    // сдвинулся или сменился размер страницы (Cycle 16, A5).
    private var sheen: Brush? = null
    private var sheenSize: Size = Size.Unspecified
    private var sheenSpec: LightPage.SheenSpec? = null

    private val watermarkPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        textAlign = android.graphics.Paint.Align.CENTER
        color = Cocoa.toArgb()
    }

    private val listener = object : SensorEventListener {
        private val rotationMatrix = FloatArray(9)
        private val orientation = FloatArray(3)

        override fun onSensorChanged(event: SensorEvent) {
            SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
            SensorManager.getOrientation(rotationMatrix, orientation)
            val next = LightPage.specFor(pitchRad = orientation[1], rollRad = orientation[2])
            if (!LightPage.isMeaningfulChange(spec, next)) return
            spec = next
            invalidateDraw()
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    fun update(watermark: String?, mirrored: Boolean) {
        this.watermark = watermark
        this.mirrored = mirrored
        invalidateDraw()
    }

    override fun onAttach() {
        // Первое чтение LocalContext и подписка на его изменения разом.
        onObservedReadsChanged()
    }

    /**
     * Читать LocalContext прямо в onAttach нельзя: колбэки узла не знают про
     * snapshot-чтения, и узел, переживший смену контекста, остался бы подписан
     * на датчик от старого. Поэтому чтение обёрнуто в observeReads — сменился
     * контекст, Compose позовёт сюда снова, и мы переподпишемся.
     */
    override fun onObservedReadsChanged() {
        observeReads {
            val context = currentValueOf(LocalContext)
            unsubscribe()
            subscribe(context)
        }
    }

    private fun subscribe(context: Context) {
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val rotation = manager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        // Датчика нет — молчим: ни подписки, ни блика, ни водяного знака.
        if (manager == null || rotation == null) return
        sensorManager = manager
        sensor = rotation
        manager.registerListener(listener, rotation, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun unsubscribe() {
        sensorManager?.unregisterListener(listener)
        sensorManager = null
        sensor = null
    }

    override fun onDetach() {
        unsubscribe()
        spec = null
        sheen = null
        sheenSpec = null
        sheenSize = Size.Unspecified
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val s = spec ?: return
        if (sheen == null || sheenSpec != s || sheenSize != size) {
            sheen = Brush.radialGradient(
                colors = listOf(Cream.copy(alpha = s.alpha), Color.Transparent),
                center = Offset(size.width * s.cxFraction, size.height * s.cyFraction),
                radius = size.maxDimension * 0.6f,
            )
            sheenSpec = s
            sheenSize = size
        }
        // Блик — мягкое световое пятно Cream, скользящее по бумаге за наклоном.
        sheen?.let { drawRect(brush = it) }

        val text = watermark ?: return
        // Водяной знак виден ровно настолько, насколько страница «на свету».
        watermarkPaint.textSize = 30.sp.toPx()
        watermarkPaint.alpha = (s.alpha * 255).toInt()
        val canvas = drawContext.canvas.nativeCanvas
        canvas.save()
        if (mirrored) canvas.scale(-1f, 1f, size.width / 2, size.height / 2)
        canvas.rotate(-14f, size.width / 2, size.height / 2)
        canvas.drawText(text, size.width / 2, size.height / 2, watermarkPaint)
        canvas.restore()
    }
}
