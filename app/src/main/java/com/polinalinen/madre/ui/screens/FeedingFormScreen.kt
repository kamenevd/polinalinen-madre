package com.polinalinen.madre.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polinalinen.madre.data.db.entities.StorageLocation
import com.polinalinen.madre.ui.components.PageLabel
import com.polinalinen.madre.ui.theme.AppColors

/**
 * Кормление закваски — экран 5. Точный порт мокапа s-feed из
 * madre-v4-prototype.html (мука/вода по 50г умолчание, Кухня/Холод —
 * штампы-переключатели, декоративная фотокарточка без реального файла —
 * как и в прототипе, заметка на полях). Cycle 3, 2026-07-21.
 */
@Composable
fun FeedingFormScreen(
    onSave: (flourGrams: Int, waterGrams: Int, location: StorageLocation, note: String?) -> Unit,
    onBack: () -> Unit,
) {
    val colors = AppColors.current
    var flourText by remember { mutableStateOf("50") }
    var waterText by remember { mutableStateOf("50") }
    var location by remember { mutableStateOf(StorageLocation.KITCHEN) }
    var note by remember { mutableStateOf("") }
    var photoAttached by remember { mutableStateOf(false) }

    Surface(color = colors.paper, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            PageLabel(
                "← Дневник",
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp).clickable { onBack() },
            )

            Text(
                "Новая запись",
                color = colors.espresso,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                modifier = Modifier.padding(horizontal = 22.dp),
            )

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                GramField("Мука", flourText, { flourText = it }, Modifier.weight(1f))
                GramField("Вода", waterText, { waterText = it }, Modifier.weight(1f))
            }

            Row(
                Modifier.padding(horizontal = 22.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LocationChip(
                    "Кухня",
                    active = location == StorageLocation.KITCHEN,
                    rotation = -2f,
                    onClick = { location = StorageLocation.KITCHEN },
                )
                LocationChip(
                    "Холод",
                    active = location == StorageLocation.FRIDGE,
                    rotation = 1f,
                    onClick = { location = StorageLocation.FRIDGE },
                )
            }

            Box(
                Modifier
                    .padding(horizontal = 22.dp, vertical = 18.dp)
                    .fillMaxWidth()
                    .rotate(1f)
                    .drawBehind { drawRect(colors.cream) }
                    .padding(10.dp)
                    .clickable { photoAttached = !photoAttached }
                    .drawBehind {
                        drawRoundRect(
                            color = colors.flour,
                            style = Stroke(
                                width = 1.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)),
                            ),
                        )
                    }
                    .padding(vertical = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (photoAttached) "фотокарточка вклеена ✦" else "вклеить фотокарточку",
                    color = colors.cocoa,
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    fontSize = 12.sp,
                )
            }

            Column(Modifier.padding(horizontal = 22.dp, vertical = 4.dp)) {
                PageLabel("Отметка на полях", color = colors.cocoa)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                        .drawBehind {
                            drawLine(
                                color = colors.flour,
                                start = Offset(0f, size.height),
                                end = Offset(size.width, size.height),
                                strokeWidth = 0.5.dp.toPx(),
                            )
                        }
                        .padding(bottom = 6.dp)
                ) {
                    if (note.isEmpty()) {
                        Text(
                            "пахнет яблоками…",
                            color = colors.flour,
                            fontFamily = FontFamily.Cursive,
                            fontSize = 16.sp,
                        )
                    }
                    BasicTextField(
                        value = note,
                        onValueChange = { note = it },
                        singleLine = true,
                        textStyle = TextStyle(color = colors.espresso, fontFamily = FontFamily.Cursive, fontSize = 16.sp),
                        cursorBrush = SolidColor(colors.crust),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Box(
                Modifier
                    .padding(horizontal = 22.dp, vertical = 22.dp)
                    .fillMaxWidth()
                    .clickable {
                        val flour = flourText.toIntOrNull() ?: 0
                        val water = waterText.toIntOrNull() ?: 0
                        val trimmedNote = note.trim()
                        onSave(flour, water, location, if (trimmedNote.isBlank()) null else trimmedNote)
                    }
                    .drawBehind {
                        drawRoundRect(colors.espresso, cornerRadius = CornerRadius(4.dp.toPx()))
                    }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Вписать в дневник", color = colors.paper, fontFamily = FontFamily.Serif, fontSize = 16.sp, letterSpacing = 1.sp)
            }
        }
    }
}

@Composable
private fun GramField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = AppColors.current
    Column(modifier) {
        PageLabel(label, color = colors.cocoa)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
                .drawBehind {
                    drawLine(
                        color = colors.espresso,
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = 1.5.dp.toPx(),
                    )
                }
                .padding(bottom = 6.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            BasicTextField(
                value = value,
                onValueChange = { new -> if (new.length <= 4 && new.all { it.isDigit() }) onChange(new) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = TextStyle(color = colors.espresso, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 24.sp),
                cursorBrush = SolidColor(colors.crust),
                modifier = Modifier.weight(1f),
            )
            Text("г", color = colors.cocoa, fontFamily = FontFamily.Serif, fontSize = 15.sp)
        }
    }
}

@Composable
private fun LocationChip(text: String, active: Boolean, rotation: Float, onClick: () -> Unit) {
    val colors = AppColors.current
    val bg = if (active) colors.espresso else Color.Transparent
    val border = if (active) colors.espresso else colors.flour
    val ink = if (active) colors.paper else colors.cocoa
    Box(
        Modifier
            .rotate(rotation)
            .clickable { onClick() }
            .drawBehind {
                drawRoundRect(bg, cornerRadius = CornerRadius(3.dp.toPx()))
                drawRoundRect(border, style = Stroke(width = 1.5.dp.toPx()), cornerRadius = CornerRadius(3.dp.toPx()))
            }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(text.uppercase(), color = ink, fontFamily = FontFamily.SansSerif, fontSize = 11.sp, letterSpacing = 2.sp)
    }
}
