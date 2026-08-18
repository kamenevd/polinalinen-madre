package com.polinalinen.madre.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import com.polinalinen.madre.ui.components.AgedPhoto
import com.polinalinen.madre.ui.components.BackLabel
import com.polinalinen.madre.ui.components.BookButton
import com.polinalinen.madre.ui.components.HairRule
import com.polinalinen.madre.ui.components.InkBlot
import com.polinalinen.madre.ui.components.InkBlotSpot
import com.polinalinen.madre.ui.components.PageLabel
import com.polinalinen.madre.ui.components.bookAction
import com.polinalinen.madre.ui.components.breathingPage
import com.polinalinen.madre.ui.components.lightPage
import com.polinalinen.madre.ui.theme.AppColors

data class DiaryEntry(
    val timeLabel: String,
    val text: String,
    val isHighlight: Boolean,
)

/**
 * Закваска — «Дневник культуры» (DESIGN-V4.md, экран 4).
 * Механики: #1 дневник от первого лица, #4 формуляр, #5 дышащая страница.
 *
 * Cycle 26: «Формуляр кормлений» — настоящая таблица с прилипающей шапкой, а
 * не раскрывающиеся карточки с прозой. Шесть узких колонок помещаются в
 * портрет целиком, история едет через LazyColumn целиком (без потолка в
 * десять строк), а длинная человеческая заметка и фотокарточка живут
 * отдельным необязательным раскрытием под строкой — они дополняют таблицу,
 * а не подменяют её.
 */
@OptIn(ExperimentalFoundationApi::class)
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
) {
    val colors = AppColors.current
    // «Клякса» — DESIGN-V4.md Cycle 3, фича InkBlot. Событие для seed — самое
    // свежее кормление (id), т.к. отдельного id у DiaryEntry/фазы нет.
    val inkEventId = history.firstOrNull()?.id ?: 0L

    // Срок следующего кормления — тот же расчёт, что и на первой полосе:
    // время последней записи плюс интервал из настроек, без «примерно».
    // Кормлений нет — строки нет: выдумывать срок не от чего.
    val nextFeedingDueMillis = history.firstOrNull()
        ?.let { FeedingSchedule.dueAtMillis(it.timestampMillis, intervalHours) }

    Surface(color = colors.paper, modifier = Modifier.fillMaxSize()) {
        // «Страница на просвет» (Cycle 4, LightPage): наклон телефона ловит
        // свет — блик по бумаге и водяной знак живой культуры. Висит на
        // «листе», не на скроллящемся списке. Без датчика — молчит.
        Box(Modifier.fillMaxSize().lightPage(watermark = "MADRE · ЖИВАЯ КУЛЬТУРА · ДЕНЬ $dayNumber")) {
        LazyColumn(
            Modifier
                .statusBarsPadding()
                .breathingPage(phase), // страница дышит — механика #5
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    BackLabel("Первая полоса", onClick = onBack)
                    PageLabel("Глава VII · день $dayNumber")
                }

                Text(
                    // Cycle 14: имя закваски — то, которым её назвали в колофоне.
                    StarterName.diaryTitle(starterName),
                    color = colors.espresso,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                    modifier = Modifier.padding(horizontal = 22.dp),
                )

                if (nextFeedingDueMillis != null) {
                    Text(
                        FeedingSchedule.nextFeedingLabel(nextFeedingDueMillis),
                        color = colors.espresso,
                        fontFamily = FontFamily.Serif,
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
                            drawRect(colors.flour, size = androidx.compose.ui.geometry.Size(2.dp.toPx(), size.height))
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
                            color = if (entry.isHighlight) colors.sage else colors.cocoa,
                            fontFamily = FontFamily.SansSerif,
                            fontSize = 11.sp,
                            letterSpacing = 1.sp,
                            modifier = Modifier.width(56.dp),
                        )
                        Text(
                            entry.text,
                            color = hand.ink,
                            fontFamily = FontFamily.Cursive,
                            fontWeight = if (entry.isHighlight) FontWeight.Bold else hand.fontWeight,
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
                        color = colors.espresso,
                        modifier = Modifier.align(Alignment.TopEnd).size(40.dp),
                    )
                }
                // Голодная фаза — размытое пятно, «будто дневник вели в спешке».
                if (phase == GrowthPhase.HUNGRY) {
                    InkBlotSpot(
                        seed = InkBlot.seedFor(inkEventId, HUNGRY_BLOT_OFFSET),
                        blurred = true,
                        color = colors.cocoa,
                        modifier = Modifier.align(Alignment.BottomStart).size(56.dp),
                    )
                }
            }
            }

            item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BookButton(label = "Покормить", onClick = onFeed, modifier = Modifier.weight(1f))
                BookButton(
                    label = "Фото",
                    onClick = onOpenGallery,
                    variant = com.polinalinen.madre.ui.components.BookButtonVariant.SECONDARY,
                    modifier = Modifier.weight(1f),
                )
            }
            PageLabel("Советы из руководства", Modifier.padding(horizontal = 22.dp, vertical = 8.dp), color = colors.espresso)
            Text(
                "В буклете Levito Madre указано соотношение базового кормления 2:1:2 (закваска:вода:мука) и стартовая гидратация 50%. " +
                    "Мадре и закваску кормят белой пшеничной мукой высшего сорта с содержанием белка >10 г на 100 г. " +
                    "Хранение в холодильнике при 4–6°C обычно даёт интервал кормления 3–5 дней. " +
                    "Пик чаще всего бывает около 3–5 часов, но фактически зависит от температуры, муки, печи и вашего ритма. " +
                    "Стартовые значения формы 50/100/50 — это редактируемые удобные массы, а не формула.",
                color = colors.cocoa, fontFamily = FontFamily.Serif, fontSize = 13.sp, lineHeight = 19.sp,
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 6.dp),
            )
            PageLabel("Формуляр кормлений", Modifier.padding(start = 22.dp, top = 10.dp), color = colors.espresso)
            }

            // Шапка липнет к верху: пролистывая историю на год назад, человек
            // всё ещё видит, какая колонка чем является.
            stickyHeader { FormularyHeader() }

            items(history, key = { feeding -> feeding.id }) { feeding ->
                FormularyRow(feeding = feeding, currentYear = currentYear)
            }
        }
        }
    }
}

/** Шесть колонок формуляра — одна ширина на шапку и на строки. */
private val COLUMN_WEIGHTS = listOf(1.35f, 0.95f, 0.95f, 0.95f, 1.0f, 1.25f)
private val COLUMN_TITLES = listOf("Дата", "Закв.", "Мука", "Вода", "Гидр.", "Хранение")
private const val FORMULARY_HEADER_DESCRIPTION =
    "Дата, Закваска, Мука, Вода, Гидратация, Хранение"

@Composable
private fun FormularyHeader() {
    val colors = AppColors.current
    Column(
        Modifier
            .fillMaxWidth()
            .drawBehind { drawRect(colors.paper) }
            .semantics {
                heading()
                contentDescription = FORMULARY_HEADER_DESCRIPTION
            },
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp)) {
            COLUMN_TITLES.forEachIndexed { index, title ->
                Text(
                    title,
                    color = colors.cocoa,
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 10.sp,
                    letterSpacing = 0.5.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    textAlign = if (index == 0) TextAlign.Start else TextAlign.End,
                    modifier = Modifier.weight(COLUMN_WEIGHTS[index]),
                )
            }
        }
        HairRule()
    }
}

/**
 * Одна строка формуляра: шесть фактов и ничего сверх них. Для TalkBack строка
 * объявляется одним предложением по порядку колонок — иначе шесть отдельных
 * чисел без подписей читаются как считалка. Незаполненная гидратация старой
 * записи объявляется «не указана», а не выдумывается.
 */
@Composable
private fun FormularyRow(feeding: FeedingEntity, currentYear: Int) {
    val colors = AppColors.current
    var expanded by rememberSaveable(feeding.id) { mutableStateOf(false) }

    val hydration = feeding.finalHydrationPercent ?: feeding.hydrationPercent
    val storageShort = if (feeding.storageLocation == StorageLocation.FRIDGE) "холод" else "кухня"
    val storageSpoken = if (feeding.storageLocation == StorageLocation.FRIDGE) "холодильник" else "кухня"
    val cells = listOf(
        RuShortDate.visible(feeding.timestampMillis, currentYear),
        feeding.retainedStarterGrams?.toString() ?: "—",
        feeding.flourGrams.toString(),
        feeding.waterGrams.toString(),
        hydration?.let { "$it%" } ?: "—",
        storageShort,
    )
    val spoken = buildString {
        append(RuShortDate.accessible(feeding.timestampMillis))
        append(". Закваска: ")
        append(feeding.retainedStarterGrams?.let { "$it граммов" } ?: "не указана")
        append(". Мука: ${feeding.flourGrams} граммов")
        append(". Вода: ${feeding.waterGrams} граммов")
        append(". Гидратация: ")
        append(hydration?.let { "$it процентов" } ?: "не указана")
        append(". Хранение: $storageSpoken.")
    }
    val detail = feeding.generatedComment ?: feeding.starterObservation
    val hasDetail = detail != null || feeding.notes != null || feeding.photoPath != null

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp, vertical = 10.dp)
                .clearAndSetSemantics { contentDescription = spoken },
        ) {
            cells.forEachIndexed { index, cell ->
                Text(
                    cell,
                    color = if (index == 0) colors.cocoa else colors.espresso,
                    fontFamily = if (index == 0) FontFamily.SansSerif else FontFamily.Serif,
                    fontSize = if (index == 0) 11.sp else 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    textAlign = if (index == 0) TextAlign.Start else TextAlign.End,
                    modifier = Modifier.weight(COLUMN_WEIGHTS[index]),
                )
            }
        }

        if (hasDetail) {
            // Отдельное действие, а не часть строки: таблица остаётся таблицей,
            // а длинный текст раскрывается по желанию. Текст раскрытия лежит
            // СНАРУЖИ нажимаемой площади — иначе TalkBack прочитал бы его
            // дважды, второй раз как подпись к кнопке.
            Box(
                Modifier
                    .padding(start = 22.dp)
                    .then(
                        bookAction(
                            if (expanded) "Свернуть подробности кормления ${RuShortDate.accessible(feeding.timestampMillis)}"
                            else "Показать подробности кормления ${RuShortDate.accessible(feeding.timestampMillis)}"
                        ) { expanded = !expanded }
                    ),
                contentAlignment = Alignment.CenterStart,
            ) {
                PageLabel(if (expanded) "свернуть" else "подробнее", color = colors.crust)
            }
            if (expanded) {
                Column(Modifier.padding(start = 22.dp, end = 22.dp, bottom = 8.dp)) {
                    detail?.let {
                        Text(
                            it,
                            color = colors.cocoa,
                            fontFamily = FontFamily.Serif,
                            fontSize = 13.sp,
                            lineHeight = 19.sp,
                        )
                    }
                    feeding.notes?.let {
                        Text(
                            "Заметка: $it",
                            color = colors.espresso,
                            fontFamily = FontFamily.Cursive,
                            fontSize = 15.sp,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    feeding.photoPath?.let {
                        Box(Modifier.padding(top = 8.dp)) {
                            AgedPhoto(
                                photoPath = it,
                                takenAtMillis = feeding.timestampMillis,
                                height = 140.dp,
                                caption = "Фотокарточка кормления",
                            )
                        }
                    }
                }
            }
        }
        HairRule()
    }
}

private const val HUNGRY_BLOT_OFFSET = 97
