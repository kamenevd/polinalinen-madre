package com.polinalinen.madre.ui.components

import com.polinalinen.madre.sourdough.GrowthPhase

/**
 * DESIGN-V4.md Cycle 6, фича «Дыхание книги» (BookBreath): механика #5
 * («Дышащая страница» дневника) расширяется на весь HomeScreen — первая
 * полоса едва заметно дышит вместе с закваской. Период и амплитуды — чистые
 * значения, вынесены из Composable ради юнит-теста (тот же приём, что
 * InkBlot/WornPage/LightPage/PhotoAging).
 */
object BookBreath {

    /** Дневник культуры — исходная механика #5: scale 1.000→1.004. */
    const val DIARY_AMPLITUDE = 1.004f

    /** Первая полоса — вдвое тише (scale 1.000→1.002): книга дышит, не двигаясь. */
    const val HOME_AMPLITUDE = 1.002f

    /** Период полувдоха: пик — быстрое дыхание, голодная — тревожное, иначе сон. */
    fun periodMillisFor(phase: GrowthPhase): Int = when (phase) {
        GrowthPhase.PEAK -> 2000
        GrowthPhase.HUNGRY -> 1500
        else -> 4000
    }
}
