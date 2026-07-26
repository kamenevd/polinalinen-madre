package com.polinalinen.madre.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Image
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polinalinen.madre.R
import com.polinalinen.madre.ui.theme.AppColors
import kotlinx.coroutines.launch

/**
 * «Откуда вклеить фотокарточку?» (Cycle 11): модалка с двумя большими тактильными
 * карточками — «Камера» и «Галерея». До этого цикла был только PhotoPicker
 * (галерея), и Дима просил именно камеру — потому что во время выпечки
 * смартфон обычно лежит на столе, а не открыт в галерее.
 *
 * Использование: bottom-sheet, который закрывается по выбору; вызывающая
 * сторона вынесла launchers наружу (gallery + camera), чтобы Composable
 * не зависел от контрактов активности.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PhotoSourceChooser(
    visible: Boolean,
    onDismiss: () -> Unit,
    onPickGallery: () -> Unit,
    onPickCamera: () -> Unit,
) {
    if (!visible) return
    val colors = AppColors.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    fun choose(action: () -> Unit) {
        scope.launch {
            sheetState.hide()
            action()
            onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = colors.paper,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp),
        ) {
            Text(
                "откуда вклеить фотокарточку?",
                color = colors.espresso,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.SemiBold,
                fontSize = 18.sp,
            )
            Text(
                "только что снятое или уже из галереи — оба пути вклеиваются одинаково",
                color = colors.cocoa,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(20.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SourceCard(
                    label = "Камера",
                    hint = "сделать снимок сейчас",
                    drawable = R.drawable.ic_camera,
                    rotation = -1.2f,
                    modifier = Modifier.weight(1f),
                    onClick = { choose(onPickCamera) },
                )
                SourceCard(
                    label = "Галерея",
                    hint = "выбрать из фото",
                    drawable = R.drawable.ic_gallery,
                    rotation = 0.9f,
                    modifier = Modifier.weight(1f),
                    onClick = { choose(onPickGallery) },
                )
            }
            Spacer(Modifier.height(14.dp))
            Surface(
                color = colors.parchment,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { choose(onDismiss) }
                    .padding(vertical = 12.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
            ) {
                Text(
                    "отмена".uppercase(),
                    color = colors.cocoa,
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 11.sp,
                    letterSpacing = 2.sp,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SourceCard(
    label: String,
    hint: String,
    drawable: Int,
    rotation: Float,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = AppColors.current
    Column(
        modifier
            .rotate(rotation)
            .clickable { onClick() }
            .drawBehind {
                drawRect(colors.cream)
                drawRoundRect(
                    color = colors.espresso,
                    cornerRadius = CornerRadius(8.dp.toPx()),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            }
            .padding(vertical = 22.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(drawable),
            contentDescription = label,
            modifier = Modifier.size(40.dp),
        )
        Spacer(Modifier.height(10.dp))
        Text(
            label,
            color = colors.espresso,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp,
        )
        Text(
            hint,
            color = colors.cocoa,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
