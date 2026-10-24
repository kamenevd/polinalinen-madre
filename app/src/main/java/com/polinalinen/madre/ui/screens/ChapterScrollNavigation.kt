package com.polinalinen.madre.ui.screens

import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.foundation.ScrollState
import androidx.compose.ui.text.TextLayoutResult

/** A seek is explicit; passive scrolling changes the bookmark without issuing another seek. */
data class ChapterScrollNavigation(
    val anchor: String,
    val request: Long,
    val pageAnchors: List<String>,
    val cursor: ChapterScrollState,
    val onAnchorChanged: (String) -> Unit,
)

/** Owned beside the reader's page, not by the transient embedded renderer. */
class ChapterScrollState(offset: Int = 0, request: Long = Long.MIN_VALUE, layout: String? = null) {
    val scroll = ScrollState(offset)
    var processed by mutableLongStateOf(request)
    var geometry by mutableStateOf(layout)
    // A transient first measure may clamp ScrollState before the whole chapter's
    // layout is ready. Keep the saved offset until that real layout can accept it.
    var pendingRestore by mutableStateOf(offset.takeIf { request != Long.MIN_VALUE })
    companion object {
        val Saver = listSaver<ChapterScrollState, Any>(
            save = { listOf(it.pendingRestore ?: it.scroll.value, it.processed, it.geometry.orEmpty()) },
            restore = { ChapterScrollState(it[0] as Int, it[1] as Long, (it[2] as String).ifEmpty { null }) },
        )
    }
}

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
