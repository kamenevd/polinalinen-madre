package com.polinalinen.madre.ui.kit

import androidx.compose.ui.graphics.Color

/** Pigments belong to illustrations, never to the palette of a screen/control. */
object Illustration {
    val CoverPages = Color(0xFFE6DCCD)
    val CoverLeather = Color(0xFF1C1714)
    val CoverLettering = Color(0xFFF4EADC)
    val CoverMuted = Color(0xFFBCA88D)
    val LoafCrust = Color(0xFFB98547)
    val LoafFlour = Color(0xFFE7C99D)
    val LoafScore = Color(0xFF8C5A26)
    val SpineInks = listOf(Color(0xFF7C3B22), Color(0xFF4F6140), Color(0xFF33424D), Color(0xFF8E6A3A))
    val ShelfWood = Color(0xFF8B6A4E)
    val OvenDark = Color(0xFF0A0807)
    val OvenGlow = Color(0x66552A12)
    val JarLid = Color(0xFFB59B78)
    val JarLidShadow = Color(0xFF806044)
    val JarLidTop = Color(0xFFCEBA9C)
    val JarDough = Color(0xFFE4CFAD)
    val JarDoughShadow = Color(0xFFB49468)
    val JarBubble = Color(0xFFF8ECD7)
    // Existing recipe-page wear pigments. They do not style service screens.
    val CrumbDark = Color(0xFF8B5A1E)
    val CrumbInk = Color(0xFF6B5544)
    val CrumbCrust = Color(0xFFC8763A)
    val CrumbFlour = Color(0xFFD9CFC2)
    val PageCream = Color(0xFFFFFCF6)
    val PageInk = Color(0xFF2D1F14)
    val PageCool = Color(0xFF5B8A8F)
    val PhotoBackdrop = Color.Black.copy(alpha = .92f)
}
