package com.polinalinen.madre.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TocLineTest {
    @Test
    fun format_hasNameLeadersAndIndex() {
        val s = TocLine.format("Хлебушек домашний", 1, leaderWidth = 24)
        assertTrue(s.startsWith("Хлебушек домашний"))
        assertTrue(s.endsWith("1"))
        assertTrue("." in s)
        assertEquals("Хлебушек домашний" + ".".repeat(24) + "1", s)
    }

    @Test
    fun contentDescription_isReadable() {
        assertEquals(
            "Хлеб «Семейный», рецепт 2",
            TocLine.contentDescription("Хлеб «Семейный»", 2),
        )
    }

    @Test
    fun eleven_recipes_style_sample() {
        val lines = listOf(
            "Хлебушек домашний" to 1,
            "Хлеб «Семейный»" to 2,
            "Пампушки с чесноком" to 11,
        ).map { (n, i) -> TocLine.format(n, i) }
        lines.forEach { line ->
            assertFalse("no favorite mark", "★" in line || "гербар" in line.lowercase())
            assertTrue(line.any { it == '.' })
        }
    }
}
