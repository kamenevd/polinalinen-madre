package com.polinalinen.madre.sourdough

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Cycle 27: голос закваски на первой полосе ходит по кругу и не повторяется,
 * пока круг не закрылся.
 */
class StarterVoiceTest {

    private fun isPictureChar(ch: Char): Boolean {
        val type = Character.getType(ch)
        return type == Character.SURROGATE.toInt() || type == Character.OTHER_SYMBOL.toInt()
    }

    @Test
    fun `there are at least forty unique lines, without emoji`() {
        assertThat(StarterVoice.LINES.size).isAtLeast(40)
        assertThat(StarterVoice.LINES.toSet()).hasSize(StarterVoice.LINES.size)
        StarterVoice.LINES.forEach { line ->
            assertThat(line.isNotBlank()).isTrue()
            assertThat(line.any { isPictureChar(it) }).isFalse()
        }
    }

    @Test
    fun `cold humidity and sourdough facts are in the cycle`() {
        val joined = StarterVoice.LINES.joinToString("\n").lowercase()
        assertThat(joined).contains("холод")
        assertThat(joined).contains("влаж")
        assertThat(joined).contains("гидратац")
        assertThat(joined).contains("мук")
        assertThat(joined).contains("пузыр")
    }

    @Test
    fun `no line repeats until the cycle wraps`() {
        val seen = mutableSetOf<Int>()
        var last = -1
        repeat(StarterVoice.LINES.size) {
            last = StarterVoice.nextIndex(last)
            assertThat(seen.add(last)).isTrue()
        }
        assertThat(seen).hasSize(StarterVoice.LINES.size)
        assertThat(StarterVoice.nextIndex(last)).isEqualTo(0)
        val (index, line) = StarterVoice.advance(last)
        assertThat(index).isEqualTo(0)
        assertThat(line).isEqualTo(StarterVoice.LINES.first())
    }

    @Test
    fun `first opening starts at the first line`() {
        val (index, line) = StarterVoice.advance(-1)
        assertThat(index).isEqualTo(0)
        assertThat(line).isEqualTo(StarterVoice.LINES.first())
    }
}
