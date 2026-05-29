package com.polinalinen.madre.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.polinalinen.madre.R

// Serif family for headings — elegant, editorial feel
val PlayfairDisplay = FontFamily(
    Font(R.font.playfair_display_regular, FontWeight.Normal),
    Font(R.font.playfair_display_bold, FontWeight.Bold),
    Font(R.font.playfair_display_italic, FontWeight.Normal),
    Font(R.font.playfair_display_semibold, FontWeight.SemiBold),
)

// Sans-serif for body — clean, modern
val NunitoSans = FontFamily(
    Font(R.font.nunito_sans_regular, FontWeight.Normal),
    Font(R.font.nunito_sans_light, FontWeight.Light),
    Font(R.font.nunito_sans_semibold, FontWeight.SemiBold),
    Font(R.font.nunito_sans_bold, FontWeight.Bold),
)

val LevitoTypography = Typography(
    // Large display — recipe title on detail screen
    displayLarge = TextStyle(
        fontFamily = PlayfairDisplay,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        color = TextPrimary
    ),

    // Medium display — recipe name in list
    displayMedium = TextStyle(
        fontFamily = PlayfairDisplay,
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 32.sp,
        color = TextPrimary
    ),

    // Small display — section headers
    displaySmall = TextStyle(
        fontFamily = PlayfairDisplay,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 28.sp,
        color = TextPrimary
    ),

    // Headline — step titles
    headlineMedium = TextStyle(
        fontFamily = PlayfairDisplay,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 26.sp,
        color = TextPrimary
    ),

    // Title — app bar, cards
    titleLarge = TextStyle(
        fontFamily = PlayfairDisplay,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        color = TextPrimary
    ),

    titleMedium = TextStyle(
        fontFamily = NunitoSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        color = TextPrimary
    ),

    // Body — descriptions, instructions
    bodyLarge = TextStyle(
        fontFamily = NunitoSans,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
        color = TextSecondary
    ),

    bodyMedium = TextStyle(
        fontFamily = NunitoSans,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = TextSecondary
    ),

    // Labels — timer, duration
    labelLarge = TextStyle(
        fontFamily = NunitoSans,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = TextAccent
    ),

    labelSmall = TextStyle(
        fontFamily = NunitoSans,
        fontWeight = FontWeight.Light,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = TextSecondary
    ),
)
