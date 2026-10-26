package com.polinalinen.madre.ui.screens

import com.polinalinen.madre.ui.kit.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polinalinen.madre.data.db.entities.FeedingEntity
import com.polinalinen.madre.data.db.entities.StorageLocation
import com.polinalinen.madre.family.FamilyHand
import com.polinalinen.madre.family.style
import com.polinalinen.madre.model.RuShortDate
import com.polinalinen.madre.notifications.FeedingSchedule
import com.polinalinen.madre.sourdough.GrowthPhase
import com.polinalinen.madre.sourdough.SourdoughProfile
import com.polinalinen.madre.sourdough.StarterName
import com.polinalinen.madre.ui.components.BackLabel
import com.polinalinen.madre.ui.components.InkBlot
import com.polinalinen.madre.ui.components.InkBlotSpot
import com.polinalinen.madre.ui.components.PageLabel
import com.polinalinen.madre.ui.components.breathingPage
import com.polinalinen.madre.ui.components.lightPage

data class DiaryEntry(
    val timeLabel: String,
    val text: String,
    val isHighlight: Boolean,
)

/** Дневник культуры: неизменённая первая полоса и журнал по календарным неделям. */
@Composable
fun StarterDiaryScreen(
    dayNumber: Int,
    phase: GrowthPhase,
    profile: SourdoughProfile,
    entries: List<DiaryEntry>,
    history: List<FeedingEntity>,
    onBack: () -> Unit,
    onFeed: () -> Unit,
    onOpenGallery: () -> Unit = {},
    cancelledBakeCount: Int = 0,
    starterName: String = StarterName.DEFAULT,
    intervalHours: Int = 24,
    currentYear: Int = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR),
    nowMillis: Long? = null,
    nowProvider: () -> Long = System::currentTimeMillis,
    lastFeedingMillis: Long? = null,
    photoCount: Int = 0,
    selectedTabIndex: Int? = null,
    onTabSelected: (Int) -> Unit = {},
    onRepeat: (FeedingEntity) -> Unit = {},
    onDelete: ((FeedingEntity) -> Unit)? = null,
    deletion: com.polinalinen.madre.sourdough.DiaryDeletionState = com.polinalinen.madre.sourdough.DiaryDeletionState(),
    onUndoDelete: (Long) -> Unit = {},
    onDeletionNoticeHeightChanged: (Dp) -> Unit = {},
) {
    val colors = LocalPaper.current
    val density = LocalDensity.current
    var deletionNoticeHeight by remember { mutableStateOf(0.dp) }
    var localTab by rememberSaveable { mutableStateOf(0) }
    val selectedTab = selectedTabIndex ?: localTab
    // «Клякса» — DESIGN-V4.md Cycle 3, фича InkBlot. Событие для seed — самое
    // свежее кормление (id), т.к. отдельного id у DiaryEntry/фазы нет.
    val inkEventId = history.firstOrNull()?.id ?: 0L

    // Срок следующего кормления — тот же расчёт, что и на первой полосе:
    // время последней записи плюс интервал из настроек, без «примерно».
    val scheduleNowMillis = rememberDiaryNowMillis(nowMillis, nowProvider)
    val nextFeedingStatus = history.firstOrNull()
        ?.let { FeedingSchedule.nextFeedingStatus(FeedingSchedule.calculate(it.timestampMillis, intervalHours, scheduleNowMillis)) }

    PaperSurface(color = colors.bg, modifier = Modifier.fillMaxSize()) {
        // «Страница на просвет» (Cycle 4, LightPage): наклон телефона ловит
        // свет — блик по бумаге и водяной знак живой культуры. Висит на
        // «листе», не на скроллящемся списке. Без датчика — молчит.
        Box(Modifier.fillMaxSize()) {
        // Dispose the lazy saveable cache on every factual book transition, even on Advice.
        // A compound row key alone would resurrect A's disclosure after A → B → A.
        key(history.firstOrNull()?.sourdoughConfigId) {
        LazyColumn(
            Modifier.testTag("diary-list")
                , // страница дышит — механика #5
            contentPadding = PaddingValues(bottom = 24.dp + if (deletion.pending != null || deletion.error != null) deletionNoticeHeight else 0.dp),
        ) {
            item {
                PaperHeader(StarterName.diaryTitle(starterName), onBack,
                    overline = "Глава VII · день $dayNumber", modifier = Modifier.padding(horizontal = 22.dp))

                DiaryHero(phase, history, lastFeedingMillis, scheduleNowMillis)

                if (nextFeedingStatus != null) {
                    Text(
                        nextFeedingStatus,
                        color = colors.ink,style = PaperType.body,
                        fontSize = 15.sp,
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
                    )
                }
            }

            // Дневник — рукописный шрифт, вертикальная линия полей слева.
            // Поверх — кляксы (InkBlot, Cycle 3): отменённая выпечка и голодная
            // фаза оставляют детерминированный след прямо на странице дневника.
            // Записей всегда единицы (их порождает вызывающий экран из одной
            // фазы), поэтому это один item, а не items.
            item {
            Box(Modifier.padding(horizontal = 22.dp, vertical = 12.dp)) {
                Column(
                    Modifier
                        .drawBehind {
                            drawRect(colors.line, size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height))
                        }
                        .padding(start = 14.dp)
                ) {
                    entries.forEach { entry ->
                    // Своя рука на запись дневника — DESIGN-V4.md Cycle 2, фича FamilyHand.
                    // userId у записи нет (DiaryEntry — не привязана к автору), поэтому
                    // fallback-ключ — стабильный хеш содержимого самой записи.
                    val hand = FamilyHand.forUser(null, (entry.timeLabel + entry.text).hashCode().toLong()).style()
                    Row(Modifier.padding(vertical = 5.dp), verticalAlignment = Alignment.Top) {
                        Text(
                            "${entry.timeLabel} —",
                            color = if (entry.isHighlight) colors.sage else colors.ink2,style = PaperType.caption,
                            fontSize = 11.sp,
                            letterSpacing = 1.sp,
                            modifier = Modifier.width(56.dp),
                        )
                        Text(
                            entry.text,
                            color = hand.ink,style = PaperType.note,
                            fontWeight = if (entry.isHighlight) FontWeight.Medium else hand.fontWeight,
                            fontSize = 16.sp,
                            lineHeight = 24.sp,
                            modifier = Modifier.rotate(hand.rotationDeg),
                        )
                    }
                    }
                }
                // Отменённая выпечка — клякса у самой свежей записи (не размытая).
                if (cancelledBakeCount > 0) {
                    InkBlotSpot(
                        seed = InkBlot.seedFor(inkEventId, cancelledBakeCount),
                        color = colors.ink,
                        modifier = Modifier.align(Alignment.TopEnd).size(40.dp),
                    )
                }
                // Голодная фаза — размытое пятно, «будто дневник вели в спешке».
                if (phase == GrowthPhase.HUNGRY) {
                    InkBlotSpot(
                        seed = InkBlot.seedFor(inkEventId, HUNGRY_BLOT_OFFSET),
                        blurred = true,
                        color = colors.ink2,
                        modifier = Modifier.align(Alignment.BottomStart).size(56.dp),
                    )
                }
            }
            }

            item {
            DiaryActions(photoCount, onFeed, onOpenGallery)
            PaperSegmented(listOf("Журнал", "Советы"), selectedTab, { localTab = it; onTabSelected(it) },
                Modifier.padding(horizontal = 22.dp), label = "Раздел дневника")
            }
            if (selectedTab == 1) {
            item {
            PageLabel("Советы из руководства", Modifier.padding(horizontal = 22.dp, vertical = 8.dp), color = colors.ink)
            Text(
                "В буклете Levito Madre указано соотношение базового кормления 2:1:2 (закваска:вода:мука) и стартовая гидратация 50%. " +
                    "Мадре и закваску кормят белой пшеничной мукой высшего сорта с содержанием белка >10 г на 100 г. " +
                    "Хранение в холодильнике при 4–6°C обычно даёт интервал кормления 3–5 дней. " +
                    "Пик чаще всего бывает около 3–5 часов, но фактически зависит от температуры, муки, печи и вашего ритма. " +
                    "Стартовые значения формы 50/100/50 — это редактируемые удобные массы, а не формула.",
                color = colors.ink2,style = PaperType.body, fontSize = 13.sp, lineHeight = 19.sp,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
            )
            }
            } else {
            journalWeeks(history.filterNot { row -> deletion.pending?.let {
                row.id == it.row.id && row.sourdoughConfigId == it.book.configId
            } == true }, scheduleNowMillis).forEach { week ->
                item(key = "week-${week.start}") {
                    Text(week.label, color = colors.ink2, style = PaperType.ui,
                        modifier = Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 24.dp, bottom = 8.dp)
                            .testTag("diary-week-${week.start}").semantics { heading() })
                }
                items(week.entries, key = { "${it.sourdoughConfigId}:${it.id}" }) { feeding ->
                    JournalRow(feeding, currentYear, onRepeat, onDelete, deletion.pending == null)
                }
            }
            }
        }
        }
        if (deletion.pending != null || deletion.error != null) {
            PaperSurface(color = colors.bg, modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged {
                deletionNoticeHeight = with(density) { it.height.toDp() }
                onDeletionNoticeHeightChanged(deletionNoticeHeight)
            }) {
                Column(Modifier.fillMaxWidth().padding(22.dp).testTag("diary-delete-status"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    val pending = deletion.pending
                    Text(deletion.error ?: if (pending?.committing == true) "Удаляем запись…" else "Запись скрыта. Можно вернуть в течение 5 секунд.",
                        color = colors.ink, style = PaperType.body,
                        modifier = Modifier.semantics { liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite })
                    if (pending != null && !pending.committing) PaperButton("Вернуть", { onUndoDelete(pending.token) },
                        Modifier.fillMaxWidth().testTag("diary-delete-undo"), fillLabelWidth = true)
                }
            }
        }
        }
    }
}

/** Same kit/variants, with full-width actions when their real labels need room. */
@Composable
private fun DiaryActions(photoCount: Int, onFeed: () -> Unit, onOpenGallery: () -> Unit) {
    val feedLabel = "+ Покормить"
    val photoLabel = "Фото $photoCount"
    val measurer = rememberTextMeasurer()
    val style = PaperType.ui.copy(fontWeight = FontWeight.Bold)
    val labelWidth = with(LocalDensity.current) {
        maxOf(measurer.measure(AnnotatedString(feedLabel), style, softWrap = false).size.width,
            measurer.measure(AnnotatedString(photoLabel), style, softWrap = false).size.width).toDp()
    }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp)) {
        // PaperButton keeps 20dp padding on each side; never shrink its type.
        if ((maxWidth - 10.dp) / 2 < labelWidth + 40.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PaperButton(feedLabel, onFeed, Modifier.fillMaxWidth(), fillLabelWidth = true)
                PaperButton(photoLabel, onOpenGallery, Modifier.fillMaxWidth(), variant = PaperButtonVariant.SECONDARY, fillLabelWidth = true)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PaperButton(feedLabel, onFeed, Modifier.weight(1f), fillLabelWidth = true)
                PaperButton(photoLabel, onOpenGallery, Modifier.weight(1f), variant = PaperButtonVariant.SECONDARY, fillLabelWidth = true)
            }
        }
    }
}

private const val HUNGRY_BLOT_OFFSET = 97
