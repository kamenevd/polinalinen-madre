@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package com.polinalinen.madre.ui.screens

import com.polinalinen.madre.ui.kit.*
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.polinalinen.madre.R
import com.polinalinen.madre.ui.components.AgedPhoto
import com.polinalinen.madre.ui.components.HairRule
import com.polinalinen.madre.ui.components.TextAction
import com.polinalinen.madre.ui.components.TracingPaper
import com.polinalinen.madre.ui.components.WaxSealStamp
import com.polinalinen.madre.ui.components.drawPhotoHolders
import com.polinalinen.madre.ui.photo.rememberPhotoAttachment
import com.polinalinen.madre.shelf.ShelfShareDecision
import com.polinalinen.madre.shelf.ShelfSharePolicy
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
    onBack: (() -> Unit)? = null,
) {
    val colors = LocalPaper.current

    val sessions by viewModel.sessions.collectAsState()
    val session = sessionId?.let { id -> sessions.find { it.id == id } }
    val completions by viewModel.completions.collectAsState()
    val completion = sessionId?.let { completions[it] }
    val photoPaths by viewModel.bakePhotoPaths.collectAsState()
    val sharingAvailable by viewModel.sharingAvailable.collectAsState()
    val finishing by viewModel.finishing.collectAsState()
    val busy = sessionId != null && finishing.contains(sessionId)
    val photoPath = sessionId?.let { photoPaths[it] }
    val sealMillis = completion?.completedAtMillis ?: session?.completedAt
    var shelfDecision by rememberSaveable(sessionId) { mutableStateOf(ShelfShareDecision.KEEP) }
    var decisionTouched by rememberSaveable(sessionId) { mutableStateOf(false) }
    LaunchedEffect(sharingAvailable,photoPath) {
        if(!sharingAvailable)shelfDecision=ShelfShareDecision.KEEP
        else if(!decisionTouched)shelfDecision=if(photoPath==null)ShelfShareDecision.PUT_WITHOUT_PHOTO else ShelfShareDecision.PUT_WITH_PHOTO
        else if(photoPath==null && shelfDecision==ShelfShareDecision.PUT_WITH_PHOTO)shelfDecision=ShelfShareDecision.PUT_WITHOUT_PHOTO
    }


    // Системная «назад» с «Испечено» — личный выбор: не ставить на полку.
    BackHandler {
        if (busy) return@BackHandler
        if(onBack!=null){onBack();return@BackHandler}
        val id = sessionId ?: return@BackHandler onHome()
        viewModel.finish(id, ShelfShareDecision.KEEP, onHome)
    }

    LaunchedEffect(sessionId) {
        viewModel.restoreCompletion(sessionId)
    }

    // Камера и галерея — одна общая дорога (ui/photo/PhotoAttachment): выбор
    // источника, «Стол оформления» и сохранение живут там, экран получает уже
    // готовый абсолютный путь.
    val openPhotoSource = rememberPhotoAttachment(
        kind = PhotoStore.PhotoKind.BAKE,
        key = sessionId ?: 0L,
        onCancelled = {},
    ) { path ->
        sessionId?.let { id ->
            viewModel.stageBakePhoto(id, path)
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

    PaperSurface(color = colors.bg, modifier = Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize()) {
        Column(
            Modifier

                .verticalScroll(rememberScrollState())
                .fillMaxSize()
                .padding(horizontal = 22.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(28.dp))
            WaxSeal(dateLabel = sealMillis?.let { romanDate(it) })
            Spacer(Modifier.height(22.dp))

            Text(
                completion?.recipeName ?: session?.recipe?.name ?: "испечено",
                color = colors.ink,style = PaperType.body,
                fontWeight = FontWeight.Medium,
                fontSize = 26.sp,
                textAlign = TextAlign.Center,
            )
            if (session != null) {
                val portions = session.trustedPortions
                Text(
                    if (portions == null) "Порции не сохранены" else "×$portions ${familyWord(portions)}",
                    color = colors.acc,style = PaperType.body,
                    fontStyle = FontStyle.Italic,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            if (session != null && session.trustedPortions == null) {
                UnknownPortionsConfirmation(session.id) { viewModel.confirmPortions(session.id, it) }
            }

            // Юбилейная приписка — на самой странице, между печатью и цифрами.
            if (milestoneNote != null) {
                Text(
                    milestoneNote,
                    color = colors.ink2,style = PaperType.note,
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
                    TextAction(
                        "заменить фотокарточку",
                        enabled = !busy,
                        onClick = { openPhotoSource() },
                    )
                    Text(
                        "·",
                        color = colors.ink2,style = PaperType.body,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                    TextAction(
                        "убрать",
                        enabled = !busy,
                        onClick = { viewModel.unstageBakePhoto(sessionId) },
                    )
                }
            } else {
                PastedPhotoPrompt(
                    enabled = !busy,
                    onPick = { openPhotoSource() },
                )
            }

            Text(
                "формуляр книги пополнен — испечено с любовью",
                color = colors.ink2,style = PaperType.body,
                fontStyle = FontStyle.Italic,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 18.dp),
            )

            if (sharingAvailable && (session != null || completion != null)) {
                BakeFinaleShareChoices(shelfDecision,photoPath!=null,busy) { decisionTouched=true;shelfDecision=it }
            }

            Spacer(Modifier.weight(1f))
            Spacer(Modifier.height(24.dp))

            PaperButton(
                label = if(onBack!=null)"Вписать в книгу" else "На главную",
                enabled = !busy && !(ShelfSharePolicy.wantsPhoto(shelfDecision) && photoPath==null),
                onClick = {
                    val id = sessionId ?: return@PaperButton onHome()
                    val needsPhoto = ShelfSharePolicy.wantsPhoto(shelfDecision)
                    if (needsPhoto && photoPath == null) {
                        return@PaperButton
                    }
                    viewModel.finish(id, shelfDecision, onHome)
                },
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        }
    }
}

/**
 * Круглая сургучная печать — центральный мотив экрана. Общая механика штампа
 * (DESIGN-V4.md §«Графический язык»/Штампы) вынесена в WaxSealStamp
 * (ui/components/BookComponents.kt, Cycle 2).
 */
@Composable internal fun BakeFinaleShareChoices(decision: ShelfShareDecision, photoAvailable: Boolean,
    busy: Boolean, onDecision: (ShelfShareDecision) -> Unit) {
    FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        PaperChip("С фото",decision==ShelfShareDecision.PUT_WITH_PHOTO,
            {onDecision(ShelfShareDecision.PUT_WITH_PHOTO)},enabled=!busy && photoAvailable)
        PaperChip("Без фото",decision==ShelfShareDecision.PUT_WITHOUT_PHOTO,
            {onDecision(ShelfShareDecision.PUT_WITHOUT_PHOTO)},enabled=!busy)
        PaperChip("Только мне",decision==ShelfShareDecision.KEEP,
            {onDecision(ShelfShareDecision.KEEP)},enabled=!busy)
    }
}

@Composable
private fun WaxSeal(dateLabel: String?, modifier: Modifier = Modifier) {
    WaxSealStamp(
        title = "ИСПЕЧЕНО",
        caption = dateLabel.orEmpty(),
        color = LocalPaper.current.sage,
        modifier = modifier,
    )
}

@Composable
private fun CompletedStat(value: String, label: String) {
    val colors = LocalPaper.current
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = colors.ink,style = PaperType.body, fontWeight = FontWeight.Medium, fontSize = 20.sp)
        Text(
            label.uppercase(),
            color = colors.ink2,style = PaperType.caption,
            fontSize = 9.sp,
            letterSpacing = 1.5.sp,
        )
    }
}

/** Пустой слот фотокарточки: пунктирная рамка + уголки-держатели, тап открывает модалку «Камера / Галерея». */
@Composable
private fun PastedPhotoPrompt(enabled: Boolean, onPick: () -> Unit) {
    val colors = LocalPaper.current
    Column(
        Modifier
            .fillMaxWidth()
            .rotate(-1f)
            .drawBehind { drawRect(colors.card) }
            .padding(10.dp)
            .then(
                com.polinalinen.madre.ui.components.bookAction(
                    label = "Вклеить фотокарточку",
                    enabled = enabled,
                    onClick = onPick,
                ),
            )
            .drawBehind {
                drawRoundRect(
                    color = colors.line,
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 5f))),
                )
                drawPhotoHolders(colors.ink2.copy(alpha = 0.55f), 14.dp.toPx())
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
            color = colors.ink2,style = PaperType.body,
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
