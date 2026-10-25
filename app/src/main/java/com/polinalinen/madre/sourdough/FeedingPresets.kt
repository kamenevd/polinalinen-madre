package com.polinalinen.madre.sourdough

import com.polinalinen.madre.data.db.entities.FeedingEntity
import kotlin.math.roundToInt

/** Presets never clamp: a clipped mass would promise a different ratio. */
object FeedingPresets {
    data class Masses(val starter: Int, val flour: Int, val water: Int) {
        val valid: Boolean get() = starter in FeedingInput.STARTER.min..FeedingInput.STARTER.max &&
            flour in FeedingInput.FLOUR.min..FeedingInput.FLOUR.max &&
            water in FeedingInput.WATER.min..FeedingInput.WATER.max
    }

    fun last(feeding: FeedingEntity?): Masses? = feeding?.let {
        val starter = it.retainedStarterGrams ?: return null
        Masses(starter, it.flourGrams, it.waterGrams).takeIf { masses -> masses.valid }
    }

    fun ratio(starter: Int, multiplier: Int): Masses? {
        val step = FeedingInput.STEP_GRAMS
        val added = (starter.toDouble() * multiplier / step).roundToInt() * step
        return Masses(starter, added, added).takeIf { it.valid }
    }
}
