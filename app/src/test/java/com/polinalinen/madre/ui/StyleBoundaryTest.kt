package com.polinalinen.madre.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Real full-main scanner. Бренд Яндекса has exactly one path+package exception:
 * ui/kit/YandexIdButton.kt owns the official un-tinted button artwork.
 * Every other style rule and the zero legacy baseline remain enforced.
 */
class StyleBoundaryTest {
    @Test fun noNewStylePrimitivesInMain() {
        val root = generateSequence(File(".").absoluteFile) { it.parentFile }
            .first { File(it, "scripts/style_boundary.py").isFile }
        val process = ProcessBuilder("python3", "scripts/style_boundary.py", "--check")
            .directory(root).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(output, 0, process.waitFor())
    }
}
