package com.polinalinen.madre.ui.kit

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import com.polinalinen.madre.R
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/** Static bundled Cyrillic faces only. No platform or synthetic bold/italic. */
object PaperType {
    private val literary = TextStyle(fontFamily = Literature, fontWeight = FontWeight.Normal, fontSynthesis = FontSynthesis.None)
    private val interfaceType = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Normal, fontSynthesis = FontSynthesis.None)
    val display = literary.copy(fontSize = 40.sp, lineHeight = 42.sp, letterSpacing = (-.8).sp)
    // Genuine static 700/opsz72 from the already bundled OFL Literata, scoped to the finale only.
    val finale = literary.copy(fontFamily=FontFamily(Font(R.font.literata_finale_700,FontWeight.Bold)),
        fontSize=92.sp,lineHeight=78.sp,letterSpacing=(-4).sp,fontWeight=FontWeight.Bold)
    val h1 = literary.copy(fontSize = 30.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium)
    val h2 = literary.copy(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Medium)
    val title = literary.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium)
    val body = literary.copy(fontSize = 16.sp, lineHeight = 25.sp)
    val note = literary.copy(fontSize = 15.sp, lineHeight = 22.sp, fontStyle = FontStyle.Italic)
    val ui = interfaceType.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold)
    val caption = interfaceType.copy(fontSize = 12.sp, lineHeight = 16.sp)
    val overline = interfaceType.copy(fontSize = 11.sp, lineHeight = 17.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.Bold)
    val number = interfaceType.copy(fontSize = 44.sp, lineHeight = 50.sp, fontWeight = FontWeight.ExtraLight, fontFeatureSettings = "tnum")
    val chapterNumber = literary.copy(fontSize = 38.sp, fontWeight = FontWeight.ExtraLight)
}

@Composable fun BookText(text: String, size: Int = 20, modifier: Modifier = Modifier,
    italic: Boolean = false, color: Color = LocalPaper.current.ink,
    weight: FontWeight = if (size >= 26) FontWeight.Medium else FontWeight.Normal,
    lineHeight: TextUnit = (size * 1.27f).sp,
) {
    Text(text, modifier, color = color, style = PaperType.body.copy(
        fontSize = size.sp, lineHeight = lineHeight, fontWeight = weight,
        fontStyle = if (italic) FontStyle.Italic else FontStyle.Normal))
}

@Composable fun SmallLabel(text: String, modifier: Modifier = Modifier, color: Color = LocalPaper.current.mut) {
    val locale = LocalConfiguration.current.locales[0]
    Text(text.uppercase(locale), modifier, color = color, style = PaperType.overline)
}
