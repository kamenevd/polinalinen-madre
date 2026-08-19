package com.polinalinen.madre.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.polinalinen.madre.R
import com.polinalinen.madre.ui.components.AgedPhoto
import com.polinalinen.madre.ui.components.BookButton
import com.polinalinen.madre.ui.components.BookButtonVariant
import com.polinalinen.madre.ui.components.HairRule
import com.polinalinen.madre.ui.components.Stamp
import com.polinalinen.madre.ui.components.TextAction
import com.polinalinen.madre.ui.components.TracingPaper
import com.polinalinen.madre.ui.components.WaxSealStamp
import com.polinalinen.madre.ui.components.drawPhotoHolders
import com.polinalinen.madre.ui.photo.rememberPhotoAttachment
import com.polinalinen.madre.shelf.ShelfSharePolicy
import com.polinalinen.madre.ui.theme.AppColors
import com.polinalinen.madre.utils.PhotoStore
import com.polinalinen.madre.viewmodel.BakingViewModel

/**
 * Готово — «Сургучная печать» (DESIGN-V4.md, экран 6). Мокап ещё не согласован —
 * визуальная композиция черновая, Дима/Полина могут её пересмотреть; данные
 * (рецепт, порции, время) настоящие, читаются из BakingSession, которую
 * BakingViewModel ещё не убрал (exitSession зовётся только из onHome).
 *
 * Фотокарточка — настоящая (Cycle 6, AgedPhoto → Cycle 11, камера и «Стол
 * оформления»): модалка «Камера / Галерея» → редактор оформления → файл в
 * internal storage → путь в BakeRecord.photoPath. Только что вклеенная —
 * цветная; в формуляре книги она будет стареть вместе с записью.
 */
@Composable
fun BakingCompleteScreen(
    sessionId: Long?,
    onHome: () -> Unit,
    viewModel: BakingViewModel = viewModel(),
) {
    val colors = AppColors.current

    // Системная кнопка «назад» со страницы «Готово» означает ровно то же, что
    // кнопка «На главную»: сессию надо закрыть, иначе она осталась бы висеть
    // в списке активных выпечек уже завершённой.
    BackHandler { onHome() }

    val sessions by viewModel.sessions.collectAsState()
    val session = sessionId?.let { id -> sessions.find { it.id == id } }
    val photoPaths by viewModel.bakePhotoPaths.collectAsState()
    val sharingAvailable by viewModel.sharingAvailable.collectAsState()
    val photoPath = sessionId?.let { photoPaths[it] }
    val context = LocalContext.current
    val sharePrefs = remember {
        context.getSharedPreferences(ShelfSharePolicy.PREFS, android.content.Context.MODE_PRIVATE)
    }
    val shareMode = remember { ShelfSharePolicy.read(sharePrefs) }
    var onShelf by rememberSaveable {
        mutableStateOf(ShelfSharePolicy.showOnShelfStamp(shareMode, sharingAvailable))
    }
    var askDismissed by rememberSaveable { mutableStateOf(false) }
    var pendingPhotoShare by rememberSaveable { mutableStateOf(false) }
    val ask = sessionId != null && session != null &&
        ShelfSharePolicy.shouldAskOnComplete(shareMode, sharingAvailable) &&
        !onShelf && !askDismissed

    // Камера и галерея — одна общая дорога (ui/photo/PhotoAttachment): выбор
    // источника, «Стол оформления» и сохранение живут там, экран получает уже
    // готовый абсолютный путь.
    val openPhotoSource = rememberPhotoAttachment(
        kind = PhotoStore.PhotoKind.BAKE,
        key = sessionId ?: 0L,
    ) { path ->
        sessionId?.let { id ->
            viewModel.attachBakePhoto(id, path)
            if (pendingPhotoShare) {
                viewModel.shareBakeStats(id, withPhoto = true)
                onShelf = true
                pendingPhotoShare = false
            }
        }
    }

    LaunchedEffect(sharingAvailable, shareMode) {
        if (ShelfSharePolicy.showOnShelfStamp(shareMode, sharingAvailable)) {
            onShelf = true
        }
    }

    // «Калька» (Cycle 9, TracingPaper): приписка Мадре карандашом на юбилейной
    // выпечке. Cycle 12: лист больше НЕ ложится поверх страницы. Он забирал
    // себе все тапы и уходил только свайпом, то есть между «испекла» и «на
    // главную» вставал слой, который надо сначала догадаться смахнуть.
    // Приписка осталась — как запись на самой странице, где ей и место.
    val bakeCounts by viewModel.bakeCounts.collectAsState()
    val totalBakes = bakeCounts.values.sum()
    val milestoneNote = TracingPaper.noteFor(totalBakes)

    Surface(color = colors.paper, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .fillMaxSize()
                .padding(horizontal = 22.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(28.dp))
            WaxSeal(dateLabel = romanDate(System.currentTimeMillis()))
            Spacer(Modifier.height(22.dp))

            Text(
                session?.recipe?.name ?: "испечено",
                color = colors.espresso,
                fontFamily = FontFamily.Serif,
                fontWeight = FontWeight.Bold,
                fontSize = 26.sp,
                textAlign = TextAlign.Center,
            )
            if (session != null) {
                val portions = session.scaleFactor.toInt().coerceAtLeast(1)
                Text(
                    "×$portions ${familyWord(portions)}",
                    color = colors.crust,
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            // Юбилейная приписка — на самой странице, между печатью и цифрами.
            if (milestoneNote != null) {
                Text(
                    milestoneNote,
                    color = colors.cocoa,
                    fontFamily = FontFamily.Cursive,
                    fontSize = 18.sp,
                    lineHeight = 26.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 14.dp, start = 8.dp, end = 8.dp)
                        .rotate(-1.5f),
                )
            }

            HairRule(Modifier.padding(vertical = 18.dp))

            if (session != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    CompletedStat("${session.totalDurationMinutes / 60}ч", "заняло")
                    CompletedStat("${session.recipe.timeline.size}", "шагов")
                }
                Spacer(Modifier.height(22.dp))
            }

            if (photoPath != null && sessionId != null) {
                AgedPhoto(
                    photoPath = photoPath,
                    takenAtMillis = System.currentTimeMillis(),
                    caption = session?.recipe?.name?.let { "Фотокарточка: $it" },
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    TextAction("заменить фотокарточку", onClick = { openPhotoSource() })
                    Text(
                        "·",
                        color = colors.cocoa,
                        fontFamily = FontFamily.Serif,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                    TextAction("убрать", onClick = { viewModel.clearBakePhoto(sessionId) })
                }
            } else {
                PastedPhotoPrompt(onPick = { openPhotoSource() })
            }

            Text(
                "формуляр книги пополнен — испечено с любовью",
                color = colors.cocoa,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 18.dp),
            )

            // Cycle 27: на полку — штамп после «всегда», либо один лист
            // «Поставить на полку?». Кнопки «Поделиться статистикой» больше нет.
            if (onShelf) {
                Stamp(
                    ShelfSharePolicy.ON_SHELF_STAMP,
                    colors.sage,
                    modifier = Modifier.padding(top = 8.dp, bottom = 10.dp),
                )
            }

            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(24.dp))

            BookButton(
                label = "На главную",
                onClick = onHome,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        }

        if (ask && sessionId != null) {
            AlertDialog(
                onDismissRequest = { askDismissed = true },
                shape = RoundedCornerShape(4.dp),
                containerColor = colors.cream,
                title = {
                    Text(
                        ShelfSharePolicy.SHEET_TITLE,
                        fontFamily = FontFamily.Serif,
                        fontSize = 20.sp,
                        color = colors.espresso,
                    )
                },
                text = {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        BookButton(
                            label = ShelfSharePolicy.PUT_LABEL,
                            onClick = {
                                viewModel.shareBakeStats(sessionId, withPhoto = false)
                                onShelf = true
                                askDismissed = true
                            },
                        )
                        BookButton(
                            label = ShelfSharePolicy.PUT_WITH_PHOTO_LABEL,
                            variant = BookButtonVariant.SECONDARY,
                            onClick = {
                                if (photoPath != null) {
                                    viewModel.shareBakeStats(sessionId, withPhoto = true)
                                    onShelf = true
                                } else {
                                    pendingPhotoShare = true
                                    openPhotoSource()
                                }
                                askDismissed = true
                            },
                        )
                        BookButton(
                            label = ShelfSharePolicy.KEEP_LABEL,
                            variant = BookButtonVariant.SECONDARY,
                            onClick = { askDismissed = true },
                        )
                    }
                },
                confirmButton = {},
            )
        }
    }
}

/**
 * Круглая сургучная печать — центральный мотив экрана. Общая механика штампа
 * (DESIGN-V4.md §«Графический язык»/Штампы) вынесена в WaxSealStamp
 * (ui/components/BookComponents.kt, Cycle 2).
 */
@Composable
private fun WaxSeal(dateLabel: String, modifier: Modifier = Modifier) {
    WaxSealStamp(title = "ИСПЕЧЕНО", caption = dateLabel, color = AppColors.current.sage, modifier = modifier)
}

@Composable
private fun CompletedStat(value: String, label: String) {
    val colors = AppColors.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = colors.espresso, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        Text(
            label.uppercase(),
            color = colors.cocoa,
            fontFamily = FontFamily.SansSerif,
            fontSize = 9.sp,
            letterSpacing = 1.5.sp,
        )
    }
}

/** Пустой слот фотокарточки: пунктирная рамка + уголки-держатели, тап открывает модалку «Камера / Галерея». */
@Composable
private fun PastedPhotoPrompt(onPick: () -> Unit) {
    val colors = AppColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .rotate(-1f)
            .drawBehind { drawRect(colors.cream) }
            .padding(10.dp)
            .clickable { onPick() }
            .drawBehind {
                drawRoundRect(
                    color = colors.flour,
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))),
                )
                drawPhotoHolders(colors.cocoa.copy(alpha = 0.55f), 14.dp.toPx())
            }
            .padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_camera),
            contentDescription = "Вклеить фотокарточку",
            modifier = Modifier.size(28.dp),
        )
        Text(
            "вклеить фотокарточку",
            color = colors.cocoa,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

private fun familyWord(n: Int) = when (n) {
    1 -> "семья"
    in 2..4 -> "семьи"
    else -> "семей"
}

/** «20.VII» — день + месяц римскими, как в формуляре (см. StarterDiaryScreen.romanDate). */
private fun romanDate(millis: Long): String {
    val roman = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII")
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    return "${cal.get(java.util.Calendar.DAY_OF_MONTH)}.${roman[cal.get(java.util.Calendar.MONTH)]}"
}
