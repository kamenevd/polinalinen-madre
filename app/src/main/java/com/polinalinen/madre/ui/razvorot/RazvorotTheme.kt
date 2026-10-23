package com.polinalinen.madre.ui.razvorot

import android.content.Context
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.*
import com.polinalinen.madre.R
import java.time.LocalTime
import kotlinx.coroutines.delay

enum class Atmosphere(val label: String) { AUTO("По часам"), DAWN("Утро"), DAY("День"), DUSK("Вечер"), NIGHT("Ночь") }
enum class ReadingMode(val label: String) { BOOK("Книга"), SLIDE("Сдвиг"), SCROLL("Прокрутка") }
val Literature = FontFamily(Font(R.font.literata), Font(R.font.literata_italic, style = FontStyle.Italic))
val Manrope = FontFamily(Font(R.font.manrope))
data class PaperColors(val bg: Color, val bg2: Color, val ink: Color, val secondary: Color, val acc: Color, val ribbon: Color, val card: Color)
val DawnPaper = PaperColors(Color(0xFFF4F1EC), Color(0xFFEAE5DD), Color(0xFF16130F), Color(0xFF625B54), Color(0xFFD2602A), Color(0xFFB83A2E), Color.White)
val NightPaper = PaperColors(Color(0xFF0D0B0A), Color(0xFF1A1512), Color(0xFFF2E9DE), Color(0xFFC0B3A5), Color(0xFFF08A4B), Color(0xFFD0503F), Color(0xFF191411))
val LocalPaper = staticCompositionLocalOf { DawnPaper }
class ReadingPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("razvorot_reading", Context.MODE_PRIVATE)
    var atmosphere by mutableStateOf(Atmosphere.entries.firstOrNull { it.name == prefs.getString("atmosphere", "AUTO") } ?: Atmosphere.AUTO)
        private set
    var mode by mutableStateOf(ReadingMode.entries.firstOrNull { it.name == prefs.getString("mode", "BOOK") } ?: ReadingMode.BOOK)
        private set
    var textSize by mutableFloatStateOf(prefs.getFloat("textSize", 18f).coerceIn(16f, 26f))
        private set
    var reducedMotion by mutableStateOf(prefs.getBoolean("reducedMotion", false))
        private set
    fun setAtmosphere(v: Atmosphere) { atmosphere = v; prefs.edit().putString("atmosphere", v.name).apply() }
    fun setMode(v: ReadingMode) { mode = v; prefs.edit().putString("mode", v.name).apply() }
    fun setSize(v: Float) { textSize = v.coerceIn(16f, 26f); prefs.edit().putFloat("textSize", textSize).apply() }
    fun setReduced(v: Boolean) { reducedMotion = v; prefs.edit().putBoolean("reducedMotion", v).apply() }
}
@Composable fun rememberReadingPreferences(): ReadingPreferences {
    val context = LocalContext.current
    return remember { ReadingPreferences(context) }
}
fun atmosphereAt(hour: Int): Atmosphere = when (hour) { in 6..10 -> Atmosphere.DAWN; in 11..17 -> Atmosphere.DAY; in 18..21 -> Atmosphere.DUSK; else -> Atmosphere.NIGHT }
@Composable fun RazvorotTheme(preferences: ReadingPreferences = rememberReadingPreferences(), force: Atmosphere? = null, content: @Composable () -> Unit) {
    var hour by remember { mutableIntStateOf(LocalTime.now().hour) }
    LaunchedEffect(Unit) { while (true) { hour = LocalTime.now().hour; delay(60_000) } }
    val atmosphere = force ?: preferences.atmosphere.let { if (it == Atmosphere.AUTO) atmosphereAt(hour) else it }
    val paper = when (atmosphere) {
        Atmosphere.NIGHT -> NightPaper
        Atmosphere.DUSK -> DawnPaper.copy(bg = Color(0xFFF1E2CC), bg2 = Color(0xFFE7D3B6), ink = Color(0xFF2A1C10), acc = Color(0xFFC24E1C))
        Atmosphere.DAY -> DawnPaper.copy(bg = Color(0xFFF8F3EA), bg2 = Color(0xFFEFE7DA))
        else -> DawnPaper
    }
    val scheme = if (atmosphere == Atmosphere.NIGHT) darkColorScheme() else lightColorScheme()
    CompositionLocalProvider(LocalPaper provides paper) {
        MaterialTheme(colorScheme = scheme.copy(primary = paper.acc, onPrimary = Color.White, background = paper.bg, onBackground = paper.ink, surface = paper.bg, onSurface = paper.ink, surfaceVariant = paper.bg2, onSurfaceVariant = paper.secondary), content = content)
    }
}
