package com.polinalinen.madre.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Cycle 12: двойной тап по «Дальше» перескакивал шаг выпечки, а по «Вписать в
 * дневник» — заводил два кормления. Окно защиты проверяется здесь, на явном
 * времени, а не наблюдением за живым экраном.
 */
class TapGateTest {

    @Test
    fun `the first tap always goes through`() {
        assertThat(TapGate().accept(0L)).isTrue()
    }

    @Test
    fun `a second tap inside the window is swallowed`() {
        val gate = TapGate(windowMillis = 600L)
        assertThat(gate.accept(1_000L)).isTrue()
        assertThat(gate.accept(1_100L)).isFalse()
        assertThat(gate.accept(1_599L)).isFalse()
    }

    @Test
    fun `a deliberate repeat after the window goes through`() {
        val gate = TapGate(windowMillis = 600L)
        assertThat(gate.accept(1_000L)).isTrue()
        assertThat(gate.accept(1_600L)).isTrue()
    }

    /**
     * Отброшенные нажатия не сдвигают окно вперёд: иначе частая дрожь пальца
     * заперла бы кнопку насовсем.
     */
    @Test
    fun `swallowed taps do not extend the window`() {
        val gate = TapGate(windowMillis = 600L)
        assertThat(gate.accept(0L)).isTrue()
        assertThat(gate.accept(500L)).isFalse()
        assertThat(gate.accept(600L)).isTrue()
    }
}
