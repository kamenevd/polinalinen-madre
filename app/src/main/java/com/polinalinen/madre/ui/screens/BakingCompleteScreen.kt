package com.polinalinen.madre.ui.screens

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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.polinalinen.madre.BuildConfig
import com.polinalinen.madre.R
import com.polinalinen.madre.model.GuestPage
import com.polinalinen.madre.ui.components.AgedPhoto
import com.polinalinen.madre.ui.components.QrCode
import com.polinalinen.madre.ui.components.HairRule
import com.polinalinen.madre.ui.components.TracingPaper
import com.polinalinen.madre.ui.components.WaxSealStamp
import com.polinalinen.madre.ui.components.drawPhotoHolders
import com.polinalinen.madre.ui.photo.rememberPhotoAttachment
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

    val sessions by viewModel.sessions.collectAsState()
    val session = sessionId?.let { id -> sessions.find { it.id == id } }
    val photoPaths by viewModel.bakePhotoPaths.collectAsState()
    val photoPath = sessionId?.let { photoPaths[it] }

    // Камера и галерея — одна общая дорога (ui/photo/PhotoAttachment): выбор
    // источника, «Стол оформления» и сохранение живут там, экран получает уже
    // готовый абсолютный путь.
    val openPhotoSource = rememberPhotoAttachment(
        kind = PhotoStore.PhotoKind.BAKE,
        key = sessionId ?: 0L,
    ) { path -> sessionId?.let { viewModel.attachBakePhoto(it, path) } }

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

            if (photoPath != null) {
                AgedPhoto(photoPath = photoPath, takenAtMillis = System.currentTimeMillis())
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                ) {
                    Text(
                        "заменить фотокарточку",
                        color = colors.crust,
                        fontFamily = FontFamily.Serif,
                        fontStyle = FontStyle.Italic,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { openPhotoSource() },
                    )
                    Text(
                        "·",
                        color = colors.cocoa,
                        fontFamily = FontFamily.Serif,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                    Text(
                        "убрать",
                        color = colors.crust,
                        fontFamily = FontFamily.Serif,
                        fontStyle = FontStyle.Italic,
                        fontSize = 12.sp,
                        modifier = Modifier.clickable { viewModel.clearBakePhoto(sessionId) },
                    )
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

            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(24.dp))

            // Cycle 5: явная отправка в общую книгу. Статистика и так уходит
            // фоном при завершении (BakingViewModel.advanceStep), но кнопка
            // делает это видимым — повторное нажатие безопасно, очередь
            // дедуплицируется по id сессии (unique work + KEEP).
            if (sessionId != null && session != null) {
                var shared by remember { mutableStateOf(false) }
                ShareStatsButton(
                    shared = shared,
                    onShare = {
                        viewModel.shareBakeStats(sessionId)
                        shared = true
                    },
                )
                Spacer(Modifier.height(10.dp))
                // «Гостевая страница» (Cycle 7, GuestPage): QR для гостей за
                // столом — отзыв с их телефона, без установки приложения.
                GuestPageSection(
                    recipeId = session.recipe.id,
                    recipeName = session.recipe.name,
                )
                Spacer(Modifier.height(10.dp))
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clickable { onHome() }
                    .drawBehind {
                        drawRoundRect(colors.espresso, cornerRadius = CornerRadius(4.dp.toPx()))
                    }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("На главную", color = colors.paper, fontFamily = FontFamily.Serif, fontSize = 16.sp, letterSpacing = 1.sp)
            }
        }
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

/**
 * «Поделиться статистикой» — вторичная кнопка: outline-рамка Espresso 1.5dp,
 * скругление 4dp (DESIGN-V4.md: бумага, не пластик), без заливки — заливка
 * только у главной CTA «На главную». После нажатия превращается в тихую
 * курсивную строку-подтверждение.
 */
@Composable
private fun ShareStatsButton(shared: Boolean, onShare: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AppColors.current
    if (shared) {
        Text(
            "статистика отправлена в общую книгу",
            color = colors.sage,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = modifier.fillMaxWidth().padding(vertical = 14.dp),
        )
    } else {
        Box(
            modifier
                .fillMaxWidth()
                .clickable { onShare() }
                .drawBehind {
                    drawRoundRect(
                        color = colors.espresso,
                        cornerRadius = CornerRadius(4.dp.toPx()),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
                .padding(vertical = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Поделиться статистикой",
                color = colors.espresso,
                fontFamily = FontFamily.Serif,
                fontSize = 15.sp,
                letterSpacing = 1.sp,
            )
        }
    }
}

/**
 * «Гостевая страница» (DESIGN-V4.md Cycle 7, фича GuestPage): вторичная
 * outline-кнопка, по нажатию разворачивается в QR — гость сканирует и
 * попадает на публичную форму PocketBase (server/pb_public/guest.html).
 * Отзыв потом проступит чужим почерком на гостевой странице рецепта.
 */
@Composable
private fun GuestPageSection(recipeId: String, recipeName: String, modifier: Modifier = Modifier) {
    val colors = AppColors.current
    var showQr by remember { mutableStateOf(false) }
    val url = GuestPage.guestUrl(BuildConfig.MADRE_API_URL, recipeId, recipeName)

    if (!showQr) {
        Box(
            modifier
                .fillMaxWidth()
                .clickable { showQr = true }
                .drawBehind {
                    drawRoundRect(
                        color = colors.espresso,
                        cornerRadius = CornerRadius(4.dp.toPx()),
                        style = Stroke(width = 1.5.dp.toPx()),
                    )
                }
                .padding(vertical = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Позвать гостей в книгу",
                color = colors.espresso,
                fontFamily = FontFamily.Serif,
                fontSize = 15.sp,
                letterSpacing = 1.sp,
            )
        }
    } else {
        Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            // QR — как вклеенная карточка: подложка Cream с лёгким поворотом,
            // тот же мотив, что фотокарточки. Поля подложки = тихая зона кода.
            Box(
                Modifier
                    .rotate(1f)
                    .drawBehind { drawRect(colors.cream) }
                    .padding(14.dp),
            ) {
                QrCode(content = url)
            }
            Text(
                "гости сканируют с телефона и оставляют пару слов —\nотзыв вклеится в гостевую страницу рецепта",
                color = colors.cocoa,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
            Text(
                url,
                color = colors.flour,
                fontFamily = FontFamily.SansSerif,
                fontSize = 9.sp,
                letterSpacing = 0.5.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
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
