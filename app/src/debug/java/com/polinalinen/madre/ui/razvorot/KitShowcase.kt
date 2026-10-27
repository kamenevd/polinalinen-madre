package com.polinalinen.madre.ui.razvorot

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.polinalinen.madre.ui.kit.*

/** Explicit local UI fixtures. No backend, accounts, phone data or production state. */
@Composable internal fun KitShowcase() {
    var name by remember { mutableStateOf("Пекарь") }
    var enabled by remember { mutableStateOf(true) }
    var selected by remember { mutableIntStateOf(0) }
    var grams by remember { mutableIntStateOf(100) }
    PaperScaffold(header = { PaperHeader("Набор компонентов F", overline = "STYLE-SYSTEM · UI/KIT", modifier = Modifier.padding(horizontal = 22.dp)) }) {
        item { PaperSection("Кнопки") {
            PaperButton(label = "Испечь", onClick = {}, variant = PaperButtonVariant.PRIMARY)
            Spacer(Modifier.height(8.dp))
            PaperButton(label = "Покормить", onClick = {}, variant = PaperButtonVariant.ACCENT)
            Spacer(Modifier.height(8.dp))
            PaperButton(label = "Фото", onClick = {}, variant = PaperButtonVariant.SECONDARY)
            PaperTextAction("Подробнее", {})
        } }
        item { PaperSection("Выбор") {
            PaperSegmented(ReadingMode.entries.map { it.label }, selected, { selected = it })
            PaperChip("Кухня", enabled, { enabled = !enabled })
            PaperSwitch(enabled, { enabled = it })
        } }
        item { PaperField(name, { name = it }, label = "Ваше имя", error = if (name.length > 24) "не больше 24 букв" else null) }
        item { PaperStepper("$grams г", { grams-- }, { grams++ }) }
        item { PaperSlider(grams.toFloat(), { grams = it.toInt() }, valueRange = 0f..500f) }
        item { PaperCard { PaperListRow("Имя закваски", note = "в дневнике и напоминаниях", value = "Мадре") } }
        item { PaperProgress(.62f, Modifier.padding(vertical = 20.dp)) }
        item { PaperEmpty("Пока пусто", "Здесь появятся записи") }
    }
}
