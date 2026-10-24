package com.polinalinen.madre.ui.kit

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polinalinen.madre.ui.components.TapGate

val PaperTouchTarget = 48.dp
enum class PaperButtonVariant { PRIMARY, ACCENT, SECONDARY, TEXT }

/** A visual pill can grow with fontScale; its accessible target never shrinks. */
@Composable fun PaperButton(
    label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    primary: Boolean? = null,
    enabled: Boolean = true, caption: String? = null, repeatable: Boolean = false,
    variant: PaperButtonVariant = PaperButtonVariant.PRIMARY,
    icon: ImageVector? = null,
) {
    val p = LocalPaper.current
    val gate = remember(repeatable) { if (repeatable) null else TapGate() }
    val interactions = remember { MutableInteractionSource() }
    val pressed by interactions.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .96f else 1f, tween(100), label = "paper-press")
    val actualVariant = primary?.let { if (it) PaperButtonVariant.ACCENT else PaperButtonVariant.SECONDARY } ?: variant
    val background = when (actualVariant) {
        PaperButtonVariant.PRIMARY -> p.ink
        PaperButtonVariant.ACCENT -> p.acc
        PaperButtonVariant.SECONDARY -> p.bg2
        PaperButtonVariant.TEXT -> Color.Transparent
    }
    val foreground = when (actualVariant) {
        PaperButtonVariant.PRIMARY -> p.bg
        PaperButtonVariant.ACCENT -> Color.White
        PaperButtonVariant.SECONDARY -> p.ink
        PaperButtonVariant.TEXT -> p.acc
    }
    Column(modifier) {
        Box(Modifier.fillMaxWidth().defaultMinSize(minWidth = 48.dp, minHeight = 50.dp).scale(scale)
            .clip(RoundedCornerShape(25.dp))
            .background(background.copy(alpha = if (enabled) 1f else .4f))
            .clickable(interactionSource = interactions, indication = null, enabled = enabled,
                role = Role.Button, onClickLabel = label) {
                if (repeatable || gate?.accept(System.currentTimeMillis()) == true) onClick()
            }.padding(horizontal = 20.dp, vertical = 14.dp), contentAlignment = Alignment.Center) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            if(icon!=null)Icon(icon,null,Modifier.size(20.dp),tint=foreground.copy(alpha=if(enabled)1f else .4f))
            Text(label, modifier=Modifier.weight(1f,fill=false),color = foreground.copy(alpha = if (enabled) 1f else .4f),
                style = if (actualVariant == PaperButtonVariant.TEXT) PaperType.ui.copy(fontSize = 13.sp, letterSpacing = 1.2.sp)
                    else PaperType.ui.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                textAlign = TextAlign.Center)
            }
        }
        if (caption != null) Text(caption, color = p.mut, style = PaperType.note,
            modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable fun PaperTextAction(label: String, onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, destructive: Boolean = false) {
    val p = LocalPaper.current
    val gate = remember { TapGate() }
    TextButton({ if (gate.accept(System.currentTimeMillis())) onClick() }, modifier.heightIn(min = 48.dp).semantics { onClick(label, null) }, enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = if (destructive) p.rib else p.acc,
            disabledContentColor = p.mut.copy(alpha = .4f))) {
        Text(label, style = PaperType.ui.copy(fontSize = 13.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, letterSpacing = 1.2.sp))
    }
}

@Composable fun PaperIconButton(onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, label: String, content: @Composable () -> Unit) {
    val p = LocalPaper.current
    IconButton(onClick, modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).semantics { onClick(label, null) }, enabled,
        colors = IconButtonDefaults.iconButtonColors(contentColor = p.ink,
            disabledContentColor = p.ink.copy(alpha = .4f)), content = content)
}

@Composable fun PaperChip(label: String, selected: Boolean, onClick: () -> Unit,
    modifier: Modifier = Modifier, enabled: Boolean = true,icon:ImageVector?=null) {
    val p = LocalPaper.current
    Box(modifier.heightIn(min = 48.dp).selectable(selected = selected, enabled = enabled,
        role = Role.RadioButton, onClick = onClick), contentAlignment = Alignment.Center) {
        Box(Modifier.alpha(if(enabled)1f else .4f).clip(RoundedCornerShape(18.dp)).background(if (selected) p.acc2 else p.bg2)
            .padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(5.dp)) {
                if(icon!=null)Icon(icon,null,Modifier.size(18.dp),tint=if(selected)p.acc else p.ink2)
                Text(label, style = PaperType.ui.copy(fontSize = 13.sp), color = if (selected) p.acc else p.mut)
            }
        }
    }
}

@Composable fun PaperSegmented(labels: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier, label: String? = null,enabled:(Int)->Boolean={true}) {
    val p = LocalPaper.current
    Row(modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(p.bg2).padding(3.dp)) {
        labels.forEachIndexed { index, optionLabel ->
            Box(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(22.dp))
                .background(if (index == selectedIndex) p.ink else Color.Transparent)
                .selectable(index == selectedIndex,enabled=enabled(index),role = Role.RadioButton, onClick = { onSelect(index) })
                .semantics {
                    onClick(if (label == null) optionLabel else "$label: $optionLabel", null)
                    if (label != null) contentDescription = "$label, $optionLabel"
                    stateDescription = "${index + 1} из ${labels.size}"
                }
                .padding(horizontal = 6.dp, vertical = 10.dp), contentAlignment = Alignment.Center) {
                Text(optionLabel, color = (if (index == selectedIndex) p.bg else p.mut).copy(alpha=if(enabled(index))1f else .4f),
                    style = PaperType.ui.copy(fontSize = 12.sp), textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable fun PaperField(value: String, onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier, label: String? = null, placeholder: String = "",
    numeric: Boolean = false, singleLine: Boolean = true, enabled: Boolean = true,
    error: String? = null, keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None) {
    val p = LocalPaper.current
    var focused by remember { mutableStateOf(false) }
    // Caller semantics/focus must reach the editable node, not its non-focusable parent.
    BasicTextField(value, onValueChange, enabled = enabled, singleLine = singleLine,
        keyboardOptions = keyboardOptions, visualTransformation = visualTransformation,
        cursorBrush = SolidColor(p.acc),
        textStyle = (if (numeric) PaperType.ui else PaperType.title).copy(color = p.ink),
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Column {
                if (label != null) SmallLabel(label, Modifier.padding(bottom = 8.dp))
                Box(Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    .clip(RoundedCornerShape(14.dp)).background(p.bg2)
                    .drawBehind {
                        if (focused || error != null) drawLine(if (error != null) p.error else p.acc,
                            Offset(14.dp.toPx(), size.height - 1.dp.toPx()),
                            Offset(size.width - 14.dp.toPx(), size.height - 1.dp.toPx()), 2.dp.toPx())
                    }.padding(horizontal = 16.dp, vertical = 14.dp)) {
                    if (value.isEmpty()) Text(placeholder, color = p.mut, style = PaperType.note)
                    inner()
                }
                if (error != null) Text(error, color = p.error, style = PaperType.note, modifier = Modifier.padding(top = 6.dp))
            }
        })
}

@Composable fun PaperStepButton(symbol: String, label: String, enabled: Boolean = true, onClick: () -> Unit) {
    val p = LocalPaper.current
    Box(Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
        .clickable(enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center) {
        Box(Modifier.size(46.dp).clip(CircleShape).background(p.card),contentAlignment=Alignment.Center) {
            Text(symbol, color = p.ink.copy(alpha = if (enabled) 1f else .4f), style = PaperType.ui.copy(fontSize=22.sp))
        }
    }
}

@Composable fun PaperStepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit,
    modifier: Modifier = Modifier, minusEnabled: Boolean = true, plusEnabled: Boolean = true, caption:String?=null) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PaperStepButton("−", "Уменьшить", minusEnabled, onMinus)
        Row(Modifier.weight(1f),horizontalArrangement=Arrangement.Center,verticalAlignment=Alignment.Bottom) {
            Text(value, color = LocalPaper.current.ink, style = PaperType.number)
            if(caption!=null)Text(caption,color=LocalPaper.current.ink2,style=PaperType.note,modifier=Modifier.padding(start=8.dp,bottom=6.dp))
        }
        PaperStepButton("+", "Увеличить", plusEnabled, onPlus)
    }
}

/** FIXES7.0.13 canonical 52×32 track, 24dp card thumb; the accessible target remains 52×48. */
@Composable fun PaperSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier, enabled: Boolean = true) {
    val p = LocalPaper.current
    val position by animateFloatAsState(if (checked) 1f else 0f, tween(180), label = "paper-switch")
    val active = enabled && onCheckedChange != null
    Box(modifier.sizeIn(minWidth = 52.dp, minHeight = 48.dp)
        .then(if (onCheckedChange != null) Modifier.toggleable(checked, enabled = active,
            role = Role.Switch, onValueChange = onCheckedChange) else Modifier), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(52.dp, 32.dp)) {
            val alpha = if (enabled) 1f else .4f
            drawRoundRect((if(checked)p.ink else p.line).copy(alpha=alpha),
                cornerRadius=androidx.compose.ui.geometry.CornerRadius(16.dp.toPx()))
            drawCircle(p.card.copy(alpha=alpha),12.dp.toPx(),Offset(16.dp.toPx()+20.dp.toPx()*position,center.y))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable fun PaperSlider(value: Float, onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier, valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0, enabled: Boolean = true, onValueChangeFinished: (() -> Unit)? = null) {
    val p = LocalPaper.current
    Slider(value, onValueChange, modifier.heightIn(min = 48.dp), enabled = enabled, valueRange = valueRange,
        steps = steps, onValueChangeFinished = onValueChangeFinished,
        colors = SliderDefaults.colors(thumbColor = p.card, activeTrackColor = p.ink,
            inactiveTrackColor = p.line, activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
        thumb = { Box(Modifier.size(22.dp).clip(CircleShape).background(p.card)
            .border(1.dp, p.mut, CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(4.dp).clip(CircleShape).background(p.ink))
        } },
        track = { state ->
            Box(Modifier.fillMaxWidth().height(2.dp).background(p.line, CircleShape)) {
                val fraction = ((state.value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth(fraction).height(2.dp).background(p.ink, CircleShape))
            }
        })
}

@Composable fun PaperTextButton(onClick: () -> Unit, modifier: Modifier = Modifier,
    enabled: Boolean = true, label: String, content: @Composable RowScope.() -> Unit) {
    TextButton(onClick, modifier.heightIn(min = 48.dp).semantics { onClick(label, null) }, enabled = enabled,
        colors = ButtonDefaults.textButtonColors(contentColor = LocalPaper.current.acc)) {
        ProvideTextStyle(PaperType.ui) { content() }
    }
}

/** Neutral kitchen flame, not a borrowed branded Alice asset. */
@Composable fun PaperAliceButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    PaperTextButton(onClick, modifier, label = "Открыть Алису") {
        Icon(Icons.Outlined.LocalFireDepartment, contentDescription = null, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text("Алиса", style = PaperType.ui)
    }
}
