package com.polinalinen.madre.ui.razvorot

import kotlin.math.cos
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/** Exact PAGE-CURLv3§4. Supersedes the softer interim material review.
 * These coefficients generate GLES2 source AND drive the CPU reference. */
internal object PaperLight {
    const val FACE = .40f
    const val AO = .24f
    const val AO_RADIUS = .45f
    const val AO_SOFT = 6f
    const val NIGHT = .6f
    const val VERSO_BASE = .60f
    const val VERSO_RANGE = .20f
    const val VERSO_TINT = .92f
    const val VERSO_NIGHT = .30f
    const val DESATURATION = .45f
    const val PRINT = .07f
    const val HIGHLIGHT = .035f
    const val HIGHLIGHT_NIGHT = .6f
    const val HIGHLIGHT_ANGLE = 1.12f
    const val HIGHLIGHT_WIDTH = .06f
    const val SHADOW = .58f
    const val SHADOW_MAX = .8f
    const val SHADOW_START = .9f
    const val SHADOW_FADE = 1.75f
    const val SHADOW_RADIUS = 1.1f
    const val SHADOW_SOFT = 20f
    const val GRAIN = .0075f
    // A16x16 integer hash stays exact even in GLES2 mediump. The old
    // fract(sin(..)*43758.5) lost its fractional bits and became a flat tint.
    fun grain(x:Int,y:Int):Float {
        val a=x and 15;val b=y and 15
        return (((a*13+b*29+a*b*3) and 15)/15f*2f-1f)*GRAIN
    }
    fun nightFactor(night:Boolean)=if(night)1f+NIGHT else 1f
    fun faceShade(theta:Float)=1f-FACE*(1f-cos(theta))
    fun versoShade(theta: Float) = VERSO_BASE + VERSO_RANGE * -cos(theta)
    fun crease(d:Float,r:Float,night:Boolean)=1f-AO*nightFactor(night)*exp(-abs(d)/(AO_RADIUS*r+AO_SOFT))
    fun highlight(theta:Float,night:Boolean)=HIGHLIGHT*(if(night)1f-HIGHLIGHT_NIGHT else 1f)*exp(-((theta-HIGHLIGHT_ANGLE)/HIGHLIGHT_WIDTH).pow(2))
    fun receiver(d:Float,r:Float,night:Boolean):Float {
        val e=d-SHADOW_START*r
        val t=((e+SHADOW_FADE*r)/(SHADOW_FADE*r)).coerceIn(0f,1f)
        val fade=if(e<0)t*t*(3f-2f*t) else 1f
        return 1f-min(SHADOW*nightFactor(night)*exp(-max(e,0f)/(SHADOW_RADIUS*r+SHADOW_SOFT))*fade,SHADOW_MAX)
    }
}
