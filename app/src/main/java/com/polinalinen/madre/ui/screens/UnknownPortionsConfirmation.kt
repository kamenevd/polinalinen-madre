package com.polinalinen.madre.ui.screens

import com.polinalinen.madre.ui.kit.*
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.polinalinen.madre.ui.components.TapCycleRow

/** The initial blank is not a claimed portion count. Only explicit confirmation makes it trusted. */
@Composable
internal fun UnknownPortionsConfirmation(sessionId: Long, onConfirm: (Int) -> Unit) {
    var chosen by rememberSaveable(sessionId) { mutableStateOf<Int?>(null) }
    Column(Modifier.padding(horizontal = 22.dp, vertical = 8.dp)) {
        TapCycleRow(
            label = "Сколько порций было?",
            value = chosen?.let { "×$it" } ?: "выбери",
            onClick = { chosen = chosen?.let { if (it == 5) 1 else it + 1 } ?: 1 },
        )
        PaperButton(
            label = "Подтвердить порции",
            enabled = chosen != null,
            onClick = { chosen?.let(onConfirm) },
        )
    }
}
