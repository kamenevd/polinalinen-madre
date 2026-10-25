package com.polinalinen.madre.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.dp

/** K01's five quiet book spines, drawn using existing F illustration pigments. */
@Composable fun PaperFamilyBooks(modifier: Modifier = Modifier) {
    val p = LocalPaper.current
    Canvas(modifier) {
        val width = 30.dp.toPx(); val gap = 8.dp.toPx()
        val start = (size.width - width * 5 - gap * 4) / 2
        val heights = listOf(70, 88, 62, 78, 58)
        val colors = listOf(p.rib, p.sage, p.bg2, p.acc, p.line)
        heights.forEachIndexed { index, height ->
            drawRoundRect(colors[index], Offset(start + index * (width + gap), size.height - height.dp.toPx()),
                Size(width, height.dp.toPx()), CornerRadius(4.dp.toPx()))
        }
    }
}

/** An eight-second presentation whose deadline/secret receipt are owned by the coordinator. */
@Composable fun PaperFamilyUndo(message: String, onUndo: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    val p = LocalPaper.current
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(p.ink).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(message, style = PaperType.ui, color = p.bg, modifier = Modifier.weight(1f).padding(vertical = 14.dp))
        PaperTextAction("ВЕРНУТЬ", onUndo, enabled = enabled)
    }
}
