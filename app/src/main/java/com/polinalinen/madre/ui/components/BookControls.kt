package com.polinalinen.madre.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polinalinen.madre.ui.kit.*

class TapGate(private val windowMillis: Long = DEFAULT_WINDOW_MILLIS) {

    private var lastAcceptedAt: Long? = null

    /** true — нажатие принято; false — оно пришло слишком быстро вслед за прошлым. */
    fun accept(nowMillis: Long): Boolean {
        val last = lastAcceptedAt
        if (last != null && nowMillis - last < windowMillis) return false
        lastAcceptedAt = nowMillis
        return true
    }

    companion object {
        /**
         * Полсекунды с запасом: человек не нажимает «Дальше» дважды осознанно
         * быстрее, а дребезг и случайный двойной тап укладываются в окно.
         */
        const val DEFAULT_WINDOW_MILLIS = 600L
    }
}

val MinTouchTarget = 48.dp
val TextActionSize = 15.sp

/** Compatibility interaction names; the kit is the sole visual owner. */
@Composable fun TextAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    PaperTextAction(label, onClick, modifier, enabled)
}

@Composable fun TapCycleRow(label: String, value: String, onClick: () -> Unit,
    modifier: Modifier = Modifier, valueColor: Color = LocalPaper.current.acc, enabled: Boolean = true) {
    PaperListRow(label, modifier.padding(horizontal = 22.dp), value = value, valueColor = valueColor,
        onClick = if (enabled) onClick else null, repeatable = true)
}

@Composable
fun bookAction(
    label: String,
    enabled: Boolean = true,
    repeatable: Boolean = false,
    onClick: () -> Unit,
): Modifier {
    val gate = remember(repeatable) { if (repeatable) null else TapGate() }
    return Modifier
        .defaultMinSize(minHeight = MinTouchTarget)
        .clickable(enabled = enabled, onClickLabel = label, role = Role.Button) {
            val accepted = repeatable || gate?.accept(System.currentTimeMillis()) == true
            if (accepted) onClick()
        }
}

@Composable fun BackLabel(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    PaperTextAction("← $text", onClick, modifier)
}

@Composable fun ConfirmDialog(title: String, message: String, confirmLabel: String, dismissLabel: String,
    onConfirm: () -> Unit, onDismiss: () -> Unit) {
    PaperConfirmDialog(title, message, confirmLabel, dismissLabel, onConfirm, onDismiss)
}
