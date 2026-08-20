package com.polinalinen.madre.sourdough

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Cycle 26: гидратация считается, а не спрашивается. Числа здесь — те самые,
 * которыми Дима принимал расчёт, плюс случаи, ради которых расчёт ведётся в
 * точной рациональной `Long` арифметике.
 */
class HydrationMathTest {

    /** Кормление из буклета: закваска 50% остаётся 50%. */
    @Test fun bookletFeedingKeepsFiftyPercent() {
        assertThat(
            HydrationMath.finalHydrationPercent(
                retainedStarterGrams = 50,
                priorHydrationPercent = 50,
                addedFlourGrams = 100,
                addedWaterGrams = 50,
            )
        ).isEqualTo(50)
    }

    /** Та же рука, но закваска была жидкой — гидратация падает до 60%. */
    @Test fun wetStarterFallsToSixtyPercent() {
        assertThat(
            HydrationMath.finalHydrationPercent(
                retainedStarterGrams = 50,
                priorHydrationPercent = 100,
                addedFlourGrams = 100,
                addedWaterGrams = 50,
            )
        ).isEqualTo(60)
    }

    /**
     * Целочисленное деление здесь врёт заметно: 7 г закваски при 45% — это
     * 4.83 г муки и 2.17 г воды. Округли их до 4 и 3 (как сделало бы деление
     * Int) — и на выходе будет 57% вместо 48%.
     */
    @Test fun smallMassesAreNotTruncatedToIntegers() {
        assertThat(
            HydrationMath.finalHydrationPercent(
                retainedStarterGrams = 7,
                priorHydrationPercent = 45,
                addedFlourGrams = 10,
                addedWaterGrams = 5,
            )
        ).isEqualTo(48)
    }

    /** На точной границе .5 должно выполняться математическое округление вверх. */
    @Test fun exactlyHalfHydrationRoundsUp() {
        assertThat(
            HydrationMath.finalHydrationPercent(
                retainedStarterGrams = 50,
                priorHydrationPercent = 50,
                addedFlourGrams = 100,
                addedWaterGrams = 100,
            )
        ).isEqualTo(88)
    }

    /** Рядом с границей .5: округление остаётся стандартным к ближайшему целому. */
    @Test fun neighborOfHalfHydrationRoundsNearest() {
        assertThat(
            HydrationMath.finalHydrationPercent(
                retainedStarterGrams = 50,
                priorHydrationPercent = 50,
                addedFlourGrams = 100,
                addedWaterGrams = 99,
            )
        ).isEqualTo(87)
    }

    /** Первое кормление считается от объявленной стартовой, а не от нуля. */
    @Test fun firstFeedingSeedsFromDeclaredInitialHydration() {
        assertThat(HydrationMath.INITIAL_LEVITO_HYDRATION_PERCENT).isEqualTo(50)
        assertThat(HydrationMath.priorHydrationPercent(emptyList())).isEqualTo(50)
        assertThat(HydrationMath.priorHydrationPercent(listOf(null, null))).isEqualTo(50)
    }

    /**
     * Прошлая гидратация — самая свежая ПОСЧИТАННАЯ (история приходит от новой
     * записи к старой). Legacy-записи без расчёта пропускаются, а не считаются
     * нулём и не заполняются задним числом.
     */
    @Test fun priorHydrationIsTheNewestSavedValue() {
        assertThat(HydrationMath.priorHydrationPercent(listOf(72, 60, 50))).isEqualTo(72)
        assertThat(HydrationMath.priorHydrationPercent(listOf(null, 60, 50))).isEqualTo(60)
    }

    /** Кормление, посчитанное сейчас, становится прошлой гидратацией следующего. */
    @Test fun savedResultFeedsTheNextCalculation() {
        val first = HydrationMath.finalHydrationPercent(
            retainedStarterGrams = 50,
            priorHydrationPercent = HydrationMath.priorHydrationPercent(emptyList()),
            addedFlourGrams = 100,
            addedWaterGrams = 70,
        )
        assertThat(first).isEqualTo(65)
        val second = HydrationMath.finalHydrationPercent(
            retainedStarterGrams = 50,
            priorHydrationPercent = HydrationMath.priorHydrationPercent(listOf(first)),
            addedFlourGrams = 100,
            addedWaterGrams = 50,
        )
        assertThat(second).isEqualTo(53)
    }
}
