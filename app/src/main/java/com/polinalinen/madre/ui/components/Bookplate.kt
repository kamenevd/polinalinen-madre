package com.polinalinen.madre.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polinalinen.madre.ui.theme.AppColors

/**
 * Название семьи — очищаем от лишних пробелов и не даём разрастись до
 * неприличных размеров. Чистая функция, вынесена из ViewModel/Composable
 * ради юнит-теста. DESIGN-V4.md Cycle 3, фича «Экслибрис» (Bookplate).
 */
object BookplateName {
    const val MAX_LENGTH = 40

    fun sanitize(raw: String): String {
        val collapsed = raw.trim().replace(Regex("\\s+"), " ")
        return collapsed.take(MAX_LENGTH)
    }
}

/**
 * Экслибрис — орнаментальная рамка с именем семьи, DESIGN-V4.md Cycle 3.
 * Настраивается один раз: пока familyName пусто — поле ввода, дальше —
 * только отображение (Cursive, вписано «от руки»). Кладётся поверх
 * существующего колофона SettingsScreen, отдельным блоком сверху.
 */
@Composable
fun Bookplate(
    familyName: String?,
    onSetName: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Cycle 19: надзаголовок задаётся снаружи. В колофоне это «Как подписана
     * книга» — вопрос, на который экслибрис отвечает; само слово «Экслибрис»
     * ответом не было. Значение по умолчанию оставлено прежним: по нему
     * снят золотой скриншот, и менять его заодно значило бы менять две вещи
     * одной правкой.
     */
    label: String = "Экслибрис",
) {
    val colors = AppColors.current
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        PageLabel(label, color = colors.cocoa)
        Column(
            Modifier
                .padding(top = 10.dp)
                .fillMaxWidth()
                .drawBehind { drawBookplateFrame(colors.espresso, colors.caramel) }
                .padding(22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (familyName.isNullOrBlank()) {
                BookplateNameInput(onSetName)
            } else {
                Text(
                    "эта книга принадлежит семье".uppercase(),
                    color = colors.cocoa,
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 9.sp,
                    letterSpacing = 2.sp,
                    textAlign = TextAlign.Center,
                )
                Text(
                    familyName,
                    color = colors.espresso,
                    fontFamily = FontFamily.Cursive,
                    fontWeight = FontWeight.Bold,
                    fontSize = 26.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun BookplateNameInput(onSetName: (String) -> Unit) {
    val colors = AppColors.current
    var draft by remember { mutableStateOf("") }

    Text(
        "впишите имя семьи — навсегда останется здесь",
        color = colors.cocoa,
        fontFamily = FontFamily.Serif,
        fontStyle = FontStyle.Italic,
        fontSize = 12.sp,
        textAlign = TextAlign.Center,
    )
    Column(
        Modifier
            .padding(top = 10.dp)
            .fillMaxWidth()
            .drawBehind {
                drawLine(
                    color = colors.flour,
                    start = Offset(0f, size.height),
                    end = Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }
            .padding(bottom = 6.dp)
    ) {
        if (draft.isEmpty()) {
            Text(
                "семья Ивановых",
                color = colors.flour,
                fontFamily = FontFamily.Cursive,
                fontSize = 22.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        BasicTextField(
            value = draft,
            onValueChange = { draft = BookplateName.sanitize(it) },
            singleLine = true,
            textStyle = TextStyle(
                color = colors.espresso,
                fontFamily = FontFamily.Cursive,
                fontSize = 22.sp,
                textAlign = TextAlign.Center,
            ),
            cursorBrush = SolidColor(colors.crust),
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (draft.isNotBlank()) {
        Text(
            "вписать навсегда",
            color = colors.crust,
            fontFamily = FontFamily.SansSerif,
            fontSize = 10.sp,
            letterSpacing = 2.sp,
            modifier = Modifier
                .padding(top = 10.dp)
                .clickable { onSetName(draft) },
        )
    }
}

/** Двойная рамка + уголки-росчерки — орнамент экслибриса. Углы ≤4dp (бумага, не пластик). */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBookplateFrame(
    ink: androidx.compose.ui.graphics.Color,
    accent: androidx.compose.ui.graphics.Color,
) {
    val corner = CornerRadius(4.dp.toPx())
    drawRoundRect(ink, style = Stroke(1.5.dp.toPx()), cornerRadius = corner)
    val inset = 7.dp.toPx()
    drawRoundRect(
        accent,
        topLeft = Offset(inset, inset),
        size = androidx.compose.ui.geometry.Size(size.width - inset * 2, size.height - inset * 2),
        style = Stroke(1.dp.toPx()),
        cornerRadius = CornerRadius(3.dp.toPx()),
    )
    // Уголки-росчерки — короткие диагональные штрихи по четырём углам, как на печатном экслибрисе.
    val tick = 10.dp.toPx()
    val stroke = Stroke(1.dp.toPx())
    val corners = listOf(
        Offset(inset, inset) to Offset(1f, 1f),
        Offset(size.width - inset, inset) to Offset(-1f, 1f),
        Offset(inset, size.height - inset) to Offset(1f, -1f),
        Offset(size.width - inset, size.height - inset) to Offset(-1f, -1f),
    )
    corners.forEach { (origin, dir) ->
        drawLine(ink, origin, origin + Offset(tick * dir.x, 0f), strokeWidth = stroke.width)
        drawLine(ink, origin, origin + Offset(0f, tick * dir.y), strokeWidth = stroke.width)
    }
}
