package com.polinalinen.madre.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polinalinen.madre.ui.components.bookAction
import com.polinalinen.madre.ui.components.TapGate
import androidx.compose.runtime.remember

@Composable fun PaperCountHeading(title: String, count: Int) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Bottom) {
        BookText(title,40,Modifier.weight(1f),weight=FontWeight.Normal)
        BookText(count.toString(),44,italic=true,color=LocalPaper.current.acc,weight=FontWeight.Normal)
    }
}

@Composable fun PaperNote(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, color = LocalPaper.current.mut, style = PaperType.note)
}

@Composable fun PaperStepParagraph(text: String, modifier: Modifier = Modifier) {
    val p=LocalPaper.current
    val paragraph=buildAnnotatedString {
        append(text)
        Regex("\\b\\d+(?:[,.]\\d+)?\\s*(?:г|мл)\\b").findAll(text).forEach { match ->
            addStyle(PaperType.ui.toSpanStyle().copy(fontWeight=FontWeight.Bold,color=p.ink),match.range.first,match.range.last+1)
        }
    }
    Text(paragraph,modifier,style=PaperType.body,color=p.ink2)
}

@Composable fun PaperChipRow(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { index, label -> PaperChip(label, selected == index, { onSelect(index) }) }
    }
}

@Composable fun PaperIngredientLine(name: String, amount: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(name, Modifier.weight(1f), style = PaperType.ui, color = LocalPaper.current.ink)
        Text(amount, style = PaperType.ui, color = LocalPaper.current.acc)
    }
}

@Composable fun PaperTocRow(number: Int, title: String, time: String, note: String,
    onClick: () -> Unit, modifier: Modifier = Modifier, activeLabel: String? = null, query: String = "", spokenTime:String=time) {
    val p = LocalPaper.current
    val label = "Глава $number, $title, $spokenTime"
    Row(modifier.fillMaxWidth().heightIn(min = 84.dp).then(bookAction(label, onClick = onClick))
        .semantics(mergeDescendants = true) { contentDescription = label }
        .padding(vertical = 14.dp), verticalAlignment = Alignment.Top) {
        BookText("%02d".format(number), 30, Modifier.width(48.dp), color = p.mut, weight = FontWeight.ExtraLight)
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                val highlighted = buildAnnotatedString {
                    append(title)
                    val start = title.indexOf(query.trim(), ignoreCase = true)
                    if (query.isNotBlank() && start >= 0) addStyle(SpanStyle(background = p.acc2), start, start + query.trim().length)
                }
                Text(highlighted, color = p.ink, style = PaperType.title.copy(fontSize = 19.sp), modifier = Modifier.weight(1f, fill = false))
                Canvas(Modifier.weight(1f).height(12.dp).padding(horizontal = 5.dp)) {
                    drawLine(p.line, Offset(0f, size.height - 2.dp.toPx()), Offset(size.width, size.height - 2.dp.toPx()),
                        1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx())))
                }
                Text(time, style = PaperType.ui.copy(fontSize = 14.sp), color = p.ink2)
            }
            Text(note, color = p.mut, style = PaperType.note, modifier = Modifier.padding(top = 5.dp))
            if (activeLabel != null) SmallLabel(activeLabel, Modifier.padding(top = 7.dp), color = p.acc)
        }
        if (activeLabel != null) Canvas(Modifier.padding(start = 9.dp).size(9.dp, 40.dp)) {
            drawPath(Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width, size.height)
                lineTo(size.width / 2, size.height - 5.dp.toPx()); lineTo(0f, size.height); close() }, p.rib)
        }
    }
    Canvas(Modifier.fillMaxWidth().height(1.dp)) { drawLine(p.line, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
}

@Composable fun PaperCompactStepper(value: String, onMinus: () -> Unit, onPlus: () -> Unit,
    onValue: () -> Unit, modifier: Modifier = Modifier, minusEnabled: Boolean = true, plusEnabled: Boolean = true) {
    Row(modifier.heightIn(min = 50.dp).clip(RoundedCornerShape(25.dp)).background(LocalPaper.current.bg2),
        verticalAlignment = Alignment.CenterVertically) {
        PaperStepButton("−", "Уменьшить порции", minusEnabled, onMinus)
        PaperTextAction(value, onValue, Modifier.widthIn(min = 46.dp))
        PaperStepButton("+", "Увеличить порции", plusEnabled, onPlus)
    }
}

@Composable fun PaperBakeCard(title: String, overline: String, image: Int, progress: Float,
    onClick: () -> Unit, modifier: Modifier = Modifier, timer: @Composable () -> Unit) {
    val p = LocalPaper.current
    PaperCard(modifier.then(bookAction("Открыть выпечку: $title", onClick = onClick))) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Image(painterResource(image), null, Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)), contentScale = ContentScale.Crop)
            Column(Modifier.weight(1f)) { SmallLabel(overline); Text(title, style = PaperType.title, color = p.ink); timer() }
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = p.mut)
        }
        PaperProgress(progress, Modifier.fillMaxWidth().padding(top = 12.dp))
    }
}

@Composable fun PaperRecipeTile(number: Int, title: String, image: Int, duration: String, note: String,
    onConfigure: () -> Unit, onStart: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalPaper.current
    val startGate=remember { TapGate() }
    Column(modifier.then(bookAction("Настроить выпечку: $title", onClick = onConfigure))) {
        Box(Modifier.fillMaxWidth().height(132.dp).clip(RoundedCornerShape(16.dp))) {
            Image(painterResource(image), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            BookText("%02d".format(number), 30, Modifier.align(Alignment.TopStart).padding(12.dp), color = Illustration.CoverLettering, weight = FontWeight.ExtraLight)
            PaperIconButton({if(startGate.accept(System.currentTimeMillis()))onStart()}, Modifier.align(Alignment.BottomEnd).padding(6.dp).clip(RoundedCornerShape(24.dp)).background(p.glass),
                label = "Испечь $title сейчас") { Icon(Icons.Outlined.PlayArrow, "Испечь $title сейчас") }
        }
        Text(title, style = PaperType.title.copy(fontSize = 17.sp), color = p.ink, modifier = Modifier.padding(top = 8.dp))
        Text(duration, style = PaperType.caption, color = p.ink2)
        Text(note, style = PaperType.note, color = p.mut)
    }
}

@Composable fun PaperStepRail(count: Int, current: Int, modifier: Modifier = Modifier) {
    val p = LocalPaper.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        repeat(count) { index -> Box(Modifier.weight(1f).height(3.dp).background(
            when { index < current -> p.ink2; index == current -> p.acc; else -> p.line }, RoundedCornerShape(2.dp))) }
    }
}
