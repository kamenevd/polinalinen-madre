package com.polinalinen.madre.ui.components

import com.polinalinen.madre.ui.kit.*
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
@Composable
fun PhotoSourceChooser(
    visible: Boolean,
    onDismiss: () -> Unit,
    onPickGallery: () -> Unit,
    onPickCamera: () -> Unit,
) {
    if (!visible) return
    val colors = LocalPaper.current
    fun choose(action: () -> Unit) {
        action()
        onDismiss()
    }
    PaperSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp),
        ) {
            Text(
                "откуда вклеить фотокарточку?",
                color = colors.ink,style = PaperType.body,
                fontWeight = FontWeight.Medium,
                fontSize = 18.sp,
            )
            Text(
                "только что снятое или уже из галереи — оба пути вклеиваются одинаково",
                color = colors.ink2,style = PaperType.body,
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
                    modifier = Modifier.weight(1f),
                    onClick = { choose(onPickCamera) },
                )
                SourceCard(
                    label = "Галерея",
                    hint = "выбрать из фото",
                    drawable = R.drawable.ic_gallery,
                    modifier = Modifier.weight(1f),
                    onClick = { choose(onPickGallery) },
                )
            }
            Spacer(Modifier.height(14.dp))
            PaperSurface(
                color = colors.bg2,
                modifier = Modifier
                    .fillMaxWidth()
                    .then(bookAction("Отмена") { choose(onDismiss) })
                    .padding(vertical = 12.dp),
                shape = androidx.compose.foundation.shape.RoundedCornerShape(25.dp),
            ) {
                Text(
                    "отмена".uppercase(),
                    color = colors.ink2,style = PaperType.caption,
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
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val colors = LocalPaper.current
    PaperCard(modifier.then(bookAction(label, onClick = onClick))) {
        Icon(if (drawable == R.drawable.ic_camera) Icons.Outlined.CameraAlt else Icons.Outlined.Image,
            contentDescription = label, modifier = Modifier.size(40.dp), tint = colors.ink)
        Spacer(Modifier.height(10.dp))
        Text(
            label,
            color = colors.ink,style = PaperType.body,
            fontWeight = FontWeight.Medium,
            fontSize = 16.sp,
        )
        Text(
            hint,
            color = colors.ink2,style = PaperType.body,
            fontStyle = FontStyle.Italic,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}
