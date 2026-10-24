package com.polinalinen.madre.ui.screens

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.text.TextLayoutResult

/** A seek is explicit; passive scrolling changes the bookmark without issuing another seek. */
data class ChapterScrollNavigation(
    val anchor: String,
    val request: Long,
    val pageAnchors: List<String>,
    val onAnchorChanged: (String) -> Unit,
)

/** Actual text-layout anchors, not estimates from page number or font size. */
internal class ChapterScrollAnchors {
    val positions = mutableStateMapOf<String, Int>()
    private val textTops = mutableMapOf<String, Int>()
    private val textLayouts = mutableMapOf<String, TextLayoutResult>()

    fun block(anchor: String, top: Int) { positions[anchor] = top.coerceAtLeast(0) }

    fun textTop(key: String, top: Int, fragments: Map<String, Int>) {
        textTops[key] = top
        resolve(key, fragments)
    }

    fun textLayout(key: String, layout: TextLayoutResult, fragments: Map<String, Int>) {
        textLayouts[key] = layout
        resolve(key, fragments)
    }

    private fun resolve(key: String, fragments: Map<String, Int>) {
        val top = textTops[key] ?: return
        val layout = textLayouts[key] ?: return
        if (layout.lineCount == 0) return
        fragments.forEach { (anchor, character) ->
            val offset = character.coerceIn(0, layout.layoutInput.text.length)
            positions[anchor] = (top + layout.getLineTop(layout.getLineForOffset(offset))).toInt().coerceAtLeast(0)
        }
    }
}

/** Word offsets use exactly the same split as the paginated renderer. */
internal fun chapterFragmentCharacters(recipeId: String, block: Int, text: String,
    anchors: List<String>, partStart: Int = 0, partLength: Int = text.length): Map<String, Int> = buildMap {
    val prefix = "$recipeId:$block:"
    anchors.filter { it.startsWith(prefix) }.forEach { anchor ->
        val words = anchor.removePrefix(prefix).toIntOrNull() ?: return@forEach
        if (words <= 0) return@forEach // Zero seeks include the section/step heading.
        val character = (text.split(' ').take(words).sumOf { it.length } + words).coerceAtMost(text.length)
        if (character in partStart..(partStart + partLength)) put(anchor, character - partStart)
    }
}
