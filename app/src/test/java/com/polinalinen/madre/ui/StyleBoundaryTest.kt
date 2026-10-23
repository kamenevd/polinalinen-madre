package com.polinalinen.madre.ui

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/** Real full-main scanner; exact PR61 legacy debt is transitional, not zero legacy. */
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
