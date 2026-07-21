package com.polinalinen.madre.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Шрифты — ТОЛЬКО системные (Georgia/Serif для заголовков, System Sans для текста).
// Кастомные TTF (playfair/nunito из v3) сознательно не переносим:
// они крашили на HarmonyOS (Huawei Pura 60) — см. v3 pitfall #29 в PROJECT-MAP.
private val Serif = FontFamily.Serif
private val Sans = FontFamily.SansSerif

// Шкала — 1:1 из DESIGN.md "Type Scale" (актуальна и для v4, палитра лишь light).
val MadreTypography = Typography(
    displayLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Bold, fontSize = 36.sp, letterSpacing = (-0.5).sp),
    displayMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, letterSpacing = (-0.3).sp),
    displaySmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, letterSpacing = (-0.3).sp),
    headlineLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 22.sp),
    headlineMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp),
    titleLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 16.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp, letterSpacing = 0.4.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Normal, fontSize = 13.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 12.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Light, fontSize = 11.sp),
)
