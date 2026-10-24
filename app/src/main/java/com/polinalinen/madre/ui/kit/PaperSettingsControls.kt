package com.polinalinen.madre.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp

/** One real editable search field, a separate labelled clear action and a 48dp accessible target. */
@Composable fun PaperSearchField(value: String, onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier, label: String = "Найти настройку",iconSize:Dp=20.dp,inputModifier:Modifier=Modifier) {
    val p = LocalPaper.current
    var focused by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().heightIn(min = 48.dp).background(p.bg2, RoundedCornerShape(14.dp))
        .drawBehind { if(focused)drawLine(p.acc,Offset(0f,size.height-1.dp.toPx()),Offset(size.width,size.height-1.dp.toPx()),2.dp.toPx()) },
        verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Search, null, Modifier.padding(start = 14.dp).size(iconSize), tint = p.ink2)
        BasicTextField(value, onValueChange, singleLine = true, textStyle = PaperType.body.copy(color = p.ink),
            cursorBrush = SolidColor(p.acc), modifier = inputModifier.weight(1f).padding(horizontal = 10.dp, vertical = 12.dp)
                .onFocusChanged { focused = it.isFocused }.semantics { contentDescription = label },
            decorationBox = { inner ->
                Box {
                    if (value.isEmpty()) Text(label, style = PaperType.note.copy(fontStyle = FontStyle.Italic), color = p.ink2)
                    inner()
                }
            })
        if (value.isNotEmpty()) PaperIconButton({ onValueChange("") },
            modifier = Modifier.semantics { contentDescription = "Очистить поиск" }, label = "Очистить поиск") {
            Icon(Icons.Outlined.Close, null, Modifier.size(20.dp), tint = p.ink2)
        }
    }
}

@Composable fun PaperSettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier, note: String? = null) {
    PaperListRow(label, modifier, note = note, trailing = {
        PaperSwitch(checked, onChange, Modifier.semantics {
            contentDescription = label
            onClick(label, null)
        })
    })
}

/** Same preferences and options in Settings and Aa; choosing a swatch changes the actual theme. */
@Composable fun PaperAtmospherePicker(preferences: ReadingPreferences, modifier: Modifier = Modifier) {
    val p = LocalPaper.current
    Row(modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Atmosphere.entries.forEachIndexed { index, choice ->
            val sample = paperAt(if (choice == Atmosphere.AUTO) LocalAtmosphere.current else choice)
            Column(Modifier.weight(1f).heightIn(min = 76.dp)
                .selectable(preferences.atmosphere == choice, role = Role.RadioButton,
                    onClick = { preferences.chooseAtmosphere(choice) })
                .semantics { onClick("Тема: ${choice.label}", null); stateDescription = "${index + 1} из 5" }
                .padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(32.dp).background(sample.bg, CircleShape)
                    .border(if (preferences.atmosphere == choice) 2.dp else 1.dp,
                        if (preferences.atmosphere == choice) p.acc else p.line, CircleShape))
                Text(choice.label, color = p.ink2, style = PaperType.caption,
                    modifier = Modifier.padding(top = 6.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
        }
    }
}
