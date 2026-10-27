package com.polinalinen.madre.model

import org.junit.Assert.*
import org.junit.Test

class BakeNumbersTest {
    @Test fun smallestFreeNumberAndNinthLimit() {
        assertEquals(1,BakeNumbers.allocate(emptyList(),emptyMap(),100L))
        assertEquals(2,BakeNumbers.allocate(listOf(1,3,4),emptyMap(),100L))
        assertEquals(9,BakeNumbers.allocate((1..8).toList(),emptyMap(),100L))
        assertNull(BakeNumbers.allocate((1..9).toList(),emptyMap(),100L))
    }
    @Test fun softQuarantineExpiresAtThirtyMinutesButDoesNotBlockLastSlot() {
        val released=100L
        assertEquals(2,BakeNumbers.allocate(emptyList(),mapOf(1 to released),released+1))
        assertEquals(2,BakeNumbers.allocate(emptyList(),mapOf(1 to released),released+BakeNumbers.COOLDOWN_MILLIS-1))
        assertEquals(1,BakeNumbers.allocate(emptyList(),mapOf(1 to released),released+BakeNumbers.COOLDOWN_MILLIS))
        assertEquals(1,BakeNumbers.allocate((2..9).toList(),mapOf(1 to released),released+1))
    }
    @Test fun wallClockRollbackDoesNotSilentlyBypassQuarantine() {
        assertEquals(2,BakeNumbers.allocate(emptyList(),mapOf(1 to 10_000L),1L))
        assertEquals(1,BakeNumbers.allocate(emptyList(),mapOf(1 to Long.MIN_VALUE),1L))
    }
    @Test fun allNineLabelsHaveWordsAndNoInternalIdFallback() {
        (1..9).forEach { assertTrue(BakeNumbers.label(it).startsWith("№"));assertTrue(BakeNumbers.word(it).isNotBlank());assertTrue(BakeNumbers.ordinal(it).isNotBlank()) }
        assertEquals("Без номера",BakeNumbers.label(872))
        assertFalse(com.polinalinen.madre.ui.components.TracingPaper.noteFor(50)!!.contains("№"))
    }
}
