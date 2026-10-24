package com.polinalinen.madre.ui.kit

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.*
import org.junit.Test

/** Owner15 AA contract, not a screenshot/pixel-position proof. Disabled text and decorative rules are exempt. */
class PaperNightContrastTest {
    private fun contrast(a:Color,b:Color):Double {
        val x=a.luminance().toDouble();val y=b.luminance().toDouble()
        return (maxOf(x,y)+.05)/(minOf(x,y)+.05)
    }
    @Test fun enabledNightTextAndSemanticColorsMeetNormalTextAaOnActualSurfaces() {
        val p=NightPaper
        for(bg in listOf(p.bg,p.bg2,p.card,p.acc2))for(fg in listOf(p.ink,p.ink2,p.mut,p.acc,p.error,p.sage))
            assertTrue("Enabled night text contrast ${contrast(fg,bg)}",contrast(fg,bg)>=4.5)
    }
    @Test fun filledMaterialActionsHaveAaForegroundAndNightHasNoElevationTint() {
        val s=paperScheme(NightPaper)
        for(pair in listOf(s.primary to s.onPrimary,s.secondary to s.onSecondary,s.error to s.onError))
            assertTrue(contrast(pair.first,pair.second)>=4.5)
        assertEquals(Color.Transparent,s.surfaceTint)
    }
}
