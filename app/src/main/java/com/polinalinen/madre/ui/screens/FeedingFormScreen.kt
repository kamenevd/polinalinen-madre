package com.polinalinen.madre.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polinalinen.madre.data.db.entities.StorageLocation
import com.polinalinen.madre.sourdough.FeedingInput
import com.polinalinen.madre.sourdough.HydrationMath
import com.polinalinen.madre.viewmodel.FeedingSaveState
import com.polinalinen.madre.ui.components.AgedPhoto
import com.polinalinen.madre.ui.components.BackLabel
import com.polinalinen.madre.ui.components.BookButton
import com.polinalinen.madre.ui.components.MinTouchTarget
import com.polinalinen.madre.ui.components.PageLabel
import com.polinalinen.madre.ui.components.TextAction
import com.polinalinen.madre.ui.components.bookAction
import com.polinalinen.madre.ui.components.drawPhotoHolders
import com.polinalinen.madre.ui.photo.rememberPhotoAttachment
import com.polinalinen.madre.ui.theme.AppColors
import com.polinalinen.madre.utils.PhotoStore
import kotlin.math.roundToInt

/**
 * Кормление закваски — экран 5.
 *
 * Cycle 26: форма перестала спрашивать то, что умеет посчитать. Человек
 * называет три массы — сколько закваски оставил в банке, сколько дал муки и
 * сколько воды, — а гидратация после кормления считается ([HydrationMath]) от
 * последней сохранённой и показывается строкой, которую нельзя редактировать.
 * Поля «подтвердите гидратацию» и штампов-наблюдений здесь больше нет: одно
 * спрашивало у человека то, что выводится из его же цифр, другое выдавало
 * выбранный ярлык за наблюдение.
 *
 * Быстрый путь для каждой массы — ползунок по пятёркам; точный — нажать на
 * само число и набрать вес с кухонных весов (47 г бывают).
 *
 * [priorHydrationPercent] — последняя сохранённая гидратация; null означает,
 * что посчитанных кормлений ещё нет, и тогда книга вслух называет источник:
 * стартовая гидратация 50% из буклета.
 */
@Composable
fun FeedingFormScreen(
    onSave: (Int, Int, Int, StorageLocation, String?, String?) -> Unit,
    onBack: () -> Unit,
    saveState: FeedingSaveState = FeedingSaveState.Idle,
    priorHydrationPercent: Int? = null,
) {
    val colors = AppColors.current
    val isSaving = saveState is FeedingSaveState.Saving

    // rememberSaveable, а не remember: поворот телефона или уход в камеру за
    // фотокарточкой раньше стирал уже набранные граммы и заметку.
    var starterGrams by rememberSaveable { mutableStateOf(FeedingInput.STARTER.default) }
    var flourGrams by rememberSaveable { mutableStateOf(FeedingInput.FLOUR.default) }
    var waterGrams by rememberSaveable { mutableStateOf(FeedingInput.WATER.default) }
    var location by rememberSaveable { mutableStateOf(StorageLocation.KITCHEN) }
    var note by rememberSaveable { mutableStateOf("") }
    var photoPath by rememberSaveable { mutableStateOf<String?>(null) }

    val priorHydration = priorHydrationPercent ?: HydrationMath.INITIAL_LEVITO_HYDRATION_PERCENT
    val finalHydration = HydrationMath.finalHydrationPercent(
        retainedStarterGrams = starterGrams,
        priorHydrationPercent = priorHydration,
        addedFlourGrams = flourGrams,
        addedWaterGrams = waterGrams,
    )
    val saveAnnouncement = when (saveState) {
        is FeedingSaveState.Saving -> "Сохраняется запись"
        is FeedingSaveState.Success -> "Кормление сохранено"
        is FeedingSaveState.Error -> "Ошибка сохранения: ${saveState.message}"
        FeedingSaveState.Idle -> null
    }

    // Ключ 0 — записи кормления ещё не существует, id появится только после
    // onSave. Имя файла и так уникально по времени, переименовывать нечего.
    val openPhotoSource = rememberPhotoAttachment(
        kind = PhotoStore.PhotoKind.FEEDING,
        key = 0L,
    ) { path -> photoPath = path }

    Surface(color = colors.paper, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 32.dp)
        ) {
            BackHandler(enabled = isSaving) {}
            BackLabel("Дневник", onClick = { if (!isSaving) onBack() }, modifier = Modifier.padding(horizontal = 22.dp))

            Text(
                "Новая запись",
                color = colors.espresso,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                modifier = Modifier.padding(horizontal = 22.dp),
            )

            GramCard(
                label = "Оставила закваски",
                accessibleLabel = "Оставленная закваска",
                range = FeedingInput.STARTER,
                grams = starterGrams,
                onChange = { starterGrams = it },
            )
            GramCard(
                label = "Дала муки",
                accessibleLabel = "Добавленная мука",
                range = FeedingInput.FLOUR,
                grams = flourGrams,
                onChange = { flourGrams = it },
            )
            GramCard(
                label = "Дала воды",
                accessibleLabel = "Добавленная вода",
                range = FeedingInput.WATER,
                grams = waterGrams,
                onChange = { waterGrams = it },
            )

            // Не поле, а результат: книга показывает, что получилось из
            // названных масс, и честно называет, от чего считала.
            Column(Modifier.padding(horizontal = 22.dp, vertical = 14.dp)) {
                PageLabel("Гидратация после кормления", color = colors.cocoa)
                Text(
                    "$finalHydration%",
                    color = colors.espresso,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .semantics {
                            contentDescription = "Гидратация после кормления: $finalHydration процентов, посчитана книгой"
                        },
                )
                Text(
                    if (priorHydrationPercent == null) {
                        "Считаем от стартовой гидратации " +
                            "${HydrationMath.INITIAL_LEVITO_HYDRATION_PERCENT}% — " +
                            "посчитанных кормлений в дневнике ещё нет"
                } else {
                    "Считаем от прошлой гидратации $priorHydration%"
                },
                color = colors.cocoa, fontFamily = FontFamily.Serif, fontSize = 13.sp,
                lineHeight = 19.sp,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                "Удобный стартовый набор (редактируемый): 50/100/50 — это просто " +
                    "начальные значения, а не формула.",
                color = colors.cocoa,
                fontFamily = FontFamily.Serif,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                modifier = Modifier.padding(top = 6.dp),
            )
            Text(
                "«Комментарий закваски» книга запишет сама — фактами этого кормления.",
                color = colors.cocoa, fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic, fontSize = 13.sp, lineHeight = 19.sp,
                    modifier = Modifier.padding(top = 6.dp),
                )
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

            // Слот фотокарточки:
            //  - пустой — нажимаемый (bookAction: роль, подпись, мишень 48dp)
            //    с пунктирной рамкой, тап открывает модалку «Камера / Галерея»;
            //  - заполненный — AgedPhoto (та же карточка, что для выпечки).
            if (photoPath == null) {
                Box(
                    Modifier
                        .padding(horizontal = 22.dp, vertical = 18.dp)
                        .fillMaxWidth()
                        .rotate(1f)
                        .drawBehind { drawRect(colors.cream) }
                        .padding(10.dp)
                        .then(bookAction("Вклеить фотокарточку кормления") { openPhotoSource() })
                        .drawBehind {
                            drawRoundRect(
                                color = colors.flour,
                                style = Stroke(
                                    width = 1.dp.toPx(),
                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f)),
                                ),
                            )
                            drawPhotoHolders(colors.cocoa.copy(alpha = 0.55f), 14.dp.toPx())
                        }
                        .padding(vertical = 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "вклеить фотокарточку",
                        color = colors.cocoa,
                        fontFamily = FontFamily.Serif,
                        fontStyle = FontStyle.Italic,
                        fontSize = 12.sp,
                    )
                }
            } else {
                Box(Modifier.padding(horizontal = 22.dp, vertical = 18.dp)) {
                    AgedPhoto(
                        photoPath = photoPath!!,
                        takenAtMillis = System.currentTimeMillis(),
                        height = 160.dp,
                        caption = "Фотокарточка кормления",
                    )
                }
                Row(
                    Modifier.padding(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TextAction("заменить фотокарточку", onClick = { openPhotoSource() })
                    TextAction("убрать", onClick = { photoPath = null })
                }
            }

            Column(Modifier.padding(horizontal = 22.dp, vertical = 4.dp)) {
                PageLabel("Заметка о кормлении", color = colors.cocoa)
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

            // Пустого кормления теперь не набрать: у каждой массы свой нижний
            // предел, и ноль в форму просто не попадает.
            saveAnnouncement?.let { announcement ->
                Text(
                    announcement,
                    color = colors.cocoa,
                    fontFamily = FontFamily.Serif,
                    fontSize = 13.sp,
                    lineHeight = 19.sp,
                    modifier = Modifier
                        .padding(start = 22.dp, end = 22.dp, top = 8.dp)
                        .semantics {
                            contentDescription = announcement
                            liveRegion = LiveRegionMode.Polite
                        },
                )
            }
            Box(Modifier.padding(horizontal = 22.dp, vertical = 22.dp)) {
                BookButton(
                    label = "Вписать в дневник",
                    enabled = saveState !is FeedingSaveState.Saving,
                    onClick = {
                        val trimmedNote = note.trim()
                        onSave(
                            starterGrams, flourGrams, waterGrams, location,
                            trimmedNote.ifBlank { null }, photoPath,
                        )
                    },
                )
            }
        }
    }
}

/**
 * Одна масса: крупное точное число, ползунок по пятёркам, кнопки «−5 г»/«+5 г»
 * и — по нажатию на само число — набор точного веса с весов.
 *
 * Ползунок здесь быстрый путь, а не единственный: 47 г он не наберёт, поэтому
 * число всегда остаётся нажимаемым.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GramCard(
    label: String,
    accessibleLabel: String,
    range: FeedingInput.Range,
    grams: Int,
    onChange: (Int) -> Unit,
) {
    val colors = AppColors.current
    var typing by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier
            .padding(horizontal = 22.dp, vertical = 8.dp)
            .fillMaxWidth()
            .drawBehind {
                drawRoundRect(colors.cream, cornerRadius = CornerRadius(4.dp.toPx()))
                drawRoundRect(
                    colors.flour,
                    cornerRadius = CornerRadius(4.dp.toPx()),
                    style = Stroke(width = 1.dp.toPx()),
                )
            }
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        PageLabel(label, color = colors.cocoa)
        if (typing) {
            GramNumberField(
                accessibleLabel = accessibleLabel,
                range = range,
                grams = grams,
                onCancel = { typing = false },
                onConfirm = {
                    onChange(it)
                    typing = false
                },
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .then(bookAction("Указать точный вес: $accessibleLabel") { typing = true })
                        .padding(vertical = 4.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        "$grams г",
                        color = colors.espresso,
                        fontFamily = FontFamily.Serif,
                        fontWeight = FontWeight.Bold,
                        fontSize = 26.sp,
                        // Иначе TalkBack прочитает «50 г» и промолчит о том, чего
                        // именно 50: на странице таких чисел три.
                        modifier = Modifier.semantics { contentDescription = "$accessibleLabel: $grams г" },
                    )
                }
                Spacer(Modifier.width(12.dp))
                Spacer(Modifier.weight(1f))
                StepButton(
                    symbol = "−5 г",
                    label = "Убавить на 5 граммов: $accessibleLabel",
                    enabled = grams > range.min,
                    onClick = { onChange(range.down(grams)) },
                )
                Spacer(Modifier.width(8.dp))
                StepButton(
                    symbol = "+5 г",
                    label = "Прибавить 5 граммов: $accessibleLabel",
                    enabled = grams < range.max,
                    onClick = { onChange(range.up(grams)) },
                )
            }
        }

        Slider(
            value = grams.coerceIn(range.min, range.max).toFloat(),
            onValueChange = { onChange(range.clamp(it.roundToInt())) },
            valueRange = range.min.toFloat()..range.max.toFloat(),
            steps = range.sliderSteps,
            colors = SliderDefaults.colors(
                thumbColor = colors.espresso,
                activeTrackColor = colors.cocoa,
                inactiveTrackColor = colors.flour,
                activeTickColor = colors.flour,
                inactiveTickColor = colors.flour,
            ),
            // Ползунок книги: засечка на линейке, а не пластиковая таблетка.
            thumb = {
                Box(
                    Modifier
                        .size(width = 10.dp, height = 26.dp)
                        .drawBehind {
                            drawRoundRect(colors.espresso, cornerRadius = CornerRadius(2.dp.toPx()))
                        }
                )
            },
            track = {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .drawBehind { drawRect(colors.flour) }
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp)
                .semantics {
                    contentDescription = "$accessibleLabel, граммы"
                    stateDescription = "$grams г"
                },
        )

        // Видимая шкала: край, значение по умолчанию, край.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf(range.min, range.default, range.max).forEach { mark ->
                Text(
                    "$mark г",
                    color = colors.cocoa,
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 11.sp,
                )
            }
        }
    }

}

/**
 * Точный вес с весов — прямо в карточке, а не в модалке поверх страницы:
 * набирают его стоя у банки, и терять из виду остальные две массы незачем.
 * Пределы те же, кратность пяти не обязательна.
 */
@Composable
private fun GramNumberField(
    accessibleLabel: String,
    range: FeedingInput.Range,
    grams: Int,
    onCancel: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    val colors = AppColors.current
    var text by rememberSaveable { mutableStateOf(grams.toString()) }
    val parsed = range.parse(text)

    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            BasicTextField(
                value = text,
                onValueChange = { new -> if (new.length <= 4 && new.all(Char::isDigit)) text = new },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                textStyle = TextStyle(
                    color = colors.espresso,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 24.sp,
                ),
                cursorBrush = SolidColor(colors.crust),
                modifier = Modifier
                    .weight(1f)
                    .drawBehind {
                        drawLine(
                            color = colors.espresso,
                            start = Offset(0f, size.height),
                            end = Offset(size.width, size.height),
                            strokeWidth = 1.5.dp.toPx(),
                        )
                    }
                    .semantics { contentDescription = "$accessibleLabel, точный вес в граммах" },
            )
            Text("г", color = colors.cocoa, fontFamily = FontFamily.Serif, fontSize = 15.sp)
        }
        Text(
            "от ${range.min} до ${range.max} г",
            color = if (parsed == null) colors.terracotta else colors.cocoa,
            fontFamily = FontFamily.Serif,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextAction("записать вес", onClick = { parsed?.let(onConfirm) }, enabled = parsed != null)
            TextAction("отмена", onClick = onCancel)
        }
    }
}

/** Кнопка на пять граммов: рамка книги, мишень 48dp, подпись для TalkBack. */
@Composable
private fun StepButton(symbol: String, label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = AppColors.current
    val ink = if (enabled) colors.espresso else colors.flour
    Box(
        Modifier
            .defaultMinSize(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
            .then(bookAction(label = label, enabled = enabled, onClick = onClick))
            .drawBehind {
                drawRoundRect(
                    ink,
                    cornerRadius = CornerRadius(3.dp.toPx()),
                    style = Stroke(width = 1.5.dp.toPx()),
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            symbol,
            color = ink,
            fontFamily = FontFamily.SansSerif,
            fontSize = 13.sp,
            letterSpacing = 1.sp,
        )
    }
}

/**
 * Штамп-переключатель места хранения. Как и другие штампы в книге (Stamp
 * в BookComponents.kt) — рамка 1.5dp, лёгкий поворот, НИКОГДА заливка
 * (DESIGN-V4.md §«Графический язык»/Штампы). Активное состояние отличается
 * насыщенностью рамки/текста, не подложкой.
 */
@Composable
private fun LocationChip(text: String, active: Boolean, rotation: Float, onClick: () -> Unit) {
    val colors = AppColors.current
    val border = if (active) colors.espresso else colors.flour
    val ink = if (active) colors.espresso else colors.cocoa
    Box(
        Modifier
            .rotate(rotation)
            // Штамп рисуется прежним, «бумажным» размером, а мишень под ним —
            // в полный палец: до Cycle 12 в него надо было попасть в 25dp.
            .defaultMinSize(minWidth = MinTouchTarget, minHeight = MinTouchTarget)
            .selectable(
                selected = active,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(vertical = 9.dp)
            .drawBehind {
                drawRoundRect(border, style = Stroke(width = 1.5.dp.toPx()), cornerRadius = CornerRadius(3.dp.toPx()))
            }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text.uppercase(), color = ink, fontFamily = FontFamily.SansSerif, fontSize = 11.sp, letterSpacing = 2.sp)
    }
}
