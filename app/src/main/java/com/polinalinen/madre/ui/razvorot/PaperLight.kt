package com.polinalinen.madre.ui.razvorot

import kotlin.math.cos

/** Designer review 23.10: warm paper verso, faint print, softer receiver shadow.
 * Shared constants also generate the GLES2 fragment source; geometry is unchanged. */
internal object PaperLight {
    const val VERSO_BASE = .84f
    const val VERSO_RANGE = .12f
    const val DESATURATION = .12f
    const val PRINT = .07f
    const val SHADOW = .28f
    const val SHADOW_MAX = .55f
    fun versoShade(theta: Float) = VERSO_BASE + VERSO_RANGE * -cos(theta)
}
