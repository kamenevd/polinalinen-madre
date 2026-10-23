package com.polinalinen.madre.ui.razvorot

import android.content.Context
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import com.polinalinen.madre.R
import java.time.LocalTime
import kotlinx.coroutines.delay

enum class Atmosphere(val label: String) { AUTO("По часам"), DAWN("Утро"), DAY("День"), DUSK("Вечер"), NIGHT("Ночь") }
enum class ReadingMode(val label: String) { BOOK("Как книга"), SLIDE("Сдвиг"), SCROLL("Прокрутка") }
val Literature = FontFamily(
    Font(R.font.literata_200,FontWeight.ExtraLight),Font(R.font.literata_300,FontWeight.Light),
    Font(R.font.literata_400,FontWeight.Normal),Font(R.font.literata_500,FontWeight.Medium),
    Font(R.font.literata_italic_300,FontWeight.Light,FontStyle.Italic),Font(R.font.literata_italic_400,FontWeight.Normal,FontStyle.Italic),Font(R.font.literata_italic_500,FontWeight.Medium,FontStyle.Italic))
val Manrope = FontFamily(Font(R.font.manrope_400,FontWeight.Normal),Font(R.font.manrope_600,FontWeight.SemiBold),Font(R.font.manrope_700,FontWeight.Bold))
internal val StaticTimerFonts=FontFamily(Font(R.font.manrope_200,FontWeight.ExtraLight),Font(R.font.manrope_400,FontWeight.Normal),Font(R.font.manrope_500,FontWeight.Medium),Font(R.font.manrope_600,FontWeight.SemiBold),Font(R.font.manrope_700,FontWeight.Bold),Font(R.font.manrope_800,FontWeight.ExtraBold))
data class PaperColors(val bg:Color,val bg2:Color,val card:Color,val ink:Color,val ink2:Color,val mut:Color,val line:Color,val acc:Color,val ribbon:Color)
val DawnPaper=PaperColors(Color(0xFFF4F1EC),Color(0xFFEAE5DD),Color.White,Color(0xFF16130F),Color(0xFF4F4943),Color(0xFF8F8880),Color(0xFFE1DBD2),Color(0xFFD2602A),Color(0xFFB83A2E))
val DayPaper=PaperColors(Color(0xFFF8F3EA),Color(0xFFEFE7DA),Color.White,Color(0xFF16130F),Color(0xFF4F4943),Color(0xFF8F8880),Color(0xFFE6DCCD),Color(0xFFD2602A),Color(0xFFB83A2E))
val DuskPaper=PaperColors(Color(0xFFF1E2CC),Color(0xFFE7D3B6),Color(0xFFFBF1E2),Color(0xFF2A1C10),Color(0xFF5F4630),Color(0xFF9A7F64),Color(0xFFDCC6A6),Color(0xFFC24E1C),Color(0xFFB83A2E))
val NightPaper=PaperColors(Color(0xFF0D0B0A),Color(0xFF1A1512),Color(0xFF191411),Color(0xFFF2E9DE),Color(0xFFC0B3A5),Color(0xFF7F7469),Color(0xFF2A231F),Color(0xFFF08A4B),Color(0xFFD0503F))
internal fun paperAt(atmosphere:Atmosphere)=when(atmosphere){Atmosphere.DAY->DayPaper;Atmosphere.DUSK->DuskPaper;Atmosphere.NIGHT->NightPaper;else->DawnPaper}
/** Every Material role is intentional: no dynamic/default violet enters chips or sliders. */
internal fun paperScheme(p:PaperColors)=lightColorScheme(
    primary=p.acc,onPrimary=p.card,primaryContainer=p.bg2,onPrimaryContainer=p.ink,inversePrimary=p.acc,
    secondary=p.acc,onSecondary=p.card,secondaryContainer=p.bg2,onSecondaryContainer=p.ink,
    tertiary=p.acc,onTertiary=p.card,tertiaryContainer=p.bg2,onTertiaryContainer=p.ink,
    background=p.bg,onBackground=p.ink,surface=p.bg,onSurface=p.ink,surfaceVariant=p.bg2,onSurfaceVariant=p.mut,
    surfaceTint=p.acc,inverseSurface=p.ink,inverseOnSurface=p.bg,
    error=p.ribbon,onError=p.card,errorContainer=p.bg2,onErrorContainer=p.ribbon,
    outline=p.line,outlineVariant=p.line,scrim=Color.Black,
    surfaceBright=p.card,surfaceDim=p.bg2,surfaceContainer=p.card,surfaceContainerHigh=p.bg2,
    surfaceContainerHighest=p.bg2,surfaceContainerLow=p.card,surfaceContainerLowest=p.bg,
    primaryFixed=p.bg2,primaryFixedDim=p.bg2,onPrimaryFixed=p.ink,onPrimaryFixedVariant=p.ink2,
    secondaryFixed=p.bg2,secondaryFixedDim=p.bg2,onSecondaryFixed=p.ink,onSecondaryFixedVariant=p.ink2,
    tertiaryFixed=p.bg2,tertiaryFixedDim=p.bg2,onTertiaryFixed=p.ink,onTertiaryFixedVariant=p.ink2)
private val PaperTypography=Typography(
    displayLarge=TextStyle(fontFamily=Literature,fontWeight=FontWeight.ExtraLight,fontSize=57.sp),
    displayMedium=TextStyle(fontFamily=Literature,fontWeight=FontWeight.Medium,fontSize=45.sp),
    displaySmall=TextStyle(fontFamily=Literature,fontWeight=FontWeight.Medium,fontSize=36.sp),
    headlineLarge=TextStyle(fontFamily=Literature,fontWeight=FontWeight.Medium,fontSize=32.sp),
    headlineMedium=TextStyle(fontFamily=Literature,fontWeight=FontWeight.Medium,fontSize=28.sp),
    headlineSmall=TextStyle(fontFamily=Literature,fontWeight=FontWeight.Medium,fontSize=26.sp),
    titleLarge=TextStyle(fontFamily=Literature,fontWeight=FontWeight.Medium,fontSize=22.sp),
    titleMedium=TextStyle(fontFamily=Literature,fontWeight=FontWeight.Medium,fontSize=18.sp),
    titleSmall=TextStyle(fontFamily=Manrope,fontWeight=FontWeight.SemiBold,fontSize=14.sp),
    bodyLarge=TextStyle(fontFamily=Literature,fontSize=16.sp,lineHeight=25.sp),
    bodyMedium=TextStyle(fontFamily=Literature,fontSize=14.sp,lineHeight=22.sp),
    bodySmall=TextStyle(fontFamily=Manrope,fontSize=12.sp,lineHeight=18.sp),
    labelLarge=TextStyle(fontFamily=Manrope,fontWeight=FontWeight.SemiBold,fontSize=14.sp),
    labelMedium=TextStyle(fontFamily=Manrope,fontWeight=FontWeight.Bold,fontSize=11.sp,letterSpacing=1.6.sp),
    labelSmall=TextStyle(fontFamily=Manrope,fontWeight=FontWeight.Bold,fontSize=10.sp,letterSpacing=1.8.sp))
val LocalPaper = staticCompositionLocalOf { DawnPaper }
val LocalAtmosphere = staticCompositionLocalOf { Atmosphere.DAWN }
class ReadingPreferences(context: Context, namespace:String="razvorot_reading") {
    private val prefs = context.getSharedPreferences(namespace, Context.MODE_PRIVATE)
    var onReducedMotionChanged:((Boolean)->Unit)?=null
    var atmosphere by mutableStateOf(Atmosphere.entries.firstOrNull { it.name == prefs.getString("atmosphere", "AUTO") } ?: Atmosphere.AUTO)
        private set
    var mode by mutableStateOf(ReadingMode.entries.firstOrNull { it.name == prefs.getString("mode", "BOOK") } ?: ReadingMode.BOOK)
        private set
    var textSize by mutableFloatStateOf(prefs.getFloat("textSize", 16f).coerceIn(14f, 22f))
        private set
    var portions by mutableFloatStateOf(prefs.getFloat("portions", 1f).coerceIn(1f, 5f))
        private set
    var haptics by mutableStateOf(prefs.getBoolean("haptics", true))
        private set
    var reducedMotion by mutableStateOf(prefs.getBoolean("reducedMotion", false))
        private set
    fun chooseAtmosphere(v: Atmosphere) { atmosphere = v; prefs.edit().putString("atmosphere", v.name).apply() }
    fun chooseMode(v: ReadingMode) { mode = v; prefs.edit().putString("mode", v.name).apply() }
    fun setSize(v: Float) { textSize = v.coerceIn(14f, 22f); prefs.edit().putFloat("textSize", textSize).apply() }
    fun choosePortions(v: Float) { portions = v.coerceIn(1f, 5f); prefs.edit().putFloat("portions", portions).apply() }
    fun chooseHaptics(v: Boolean) { haptics = v; prefs.edit().putBoolean("haptics", v).apply() }
    fun setReduced(v: Boolean) { reducedMotion = v; prefs.edit().putBoolean("reducedMotion", v).apply();onReducedMotionChanged?.invoke(v) }
}
@Composable fun rememberReadingPreferences(): ReadingPreferences {
    val context = LocalContext.current
    return remember { ReadingPreferences(context) }
}
fun atmosphereAt(hour: Int): Atmosphere = when (hour) { in 5..10 -> Atmosphere.DAWN; in 11..16 -> Atmosphere.DAY; in 17..21 -> Atmosphere.DUSK; else -> Atmosphere.NIGHT }
@Composable fun RazvorotTheme(preferences: ReadingPreferences = rememberReadingPreferences(), force: Atmosphere? = null, content: @Composable () -> Unit) {
    var hour by remember { mutableIntStateOf(LocalTime.now().hour) }
    LaunchedEffect(Unit) { while (true) { hour = LocalTime.now().hour; delay(60_000) } }
    val atmosphere = force ?: preferences.atmosphere.let { if (it == Atmosphere.AUTO) atmosphereAt(hour) else it }
    val paper=paperAt(atmosphere)
    val scheme=paperScheme(paper)
    CompositionLocalProvider(LocalPaper provides paper,LocalAtmosphere provides atmosphere,LocalContentColor provides paper.ink) {
        MaterialTheme(colorScheme=scheme,typography=PaperTypography,content=content)
    }
}
