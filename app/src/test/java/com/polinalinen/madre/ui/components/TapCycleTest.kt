package com.polinalinen.madre.ui.components

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TapCycleTest {

    @Test
    fun `next wraps around`() {
        val options = listOf("a", "b", "c")
        assertThat(TapCycle.next(options, "a")).isEqualTo("b")
        assertThat(TapCycle.next(options, "c")).isEqualTo("a")
    }

    @Test
    fun `unknown current falls back to first`() {
        val options = listOf("a", "b", "c")
        assertThat(TapCycle.next(options, "z")).isEqualTo("a")
    }

    @Test
    fun `single option stays on itself`() {
        assertThat(TapCycle.next(listOf("only"), "only")).isEqualTo("only")
        assertThat(TapCycle.next(listOf("only"), "other")).isEqualTo("only")
    }
}
