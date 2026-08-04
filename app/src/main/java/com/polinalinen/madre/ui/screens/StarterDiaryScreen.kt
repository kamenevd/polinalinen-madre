package com.polinalinen.madre.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polinalinen.madre.data.db.entities.FeedingEntity
import com.polinalinen.madre.family.FamilyHand
import com.polinalinen.madre.family.style
import com.polinalinen.madre.sourdough.DiaryEntry
import com.polinalinen.madre.sourdough.GrowthPhase
import com.polinalinen.madre.sourdough.MadreVoice
import com.polinalinen.madre.sourdough.SourdoughProfile
import com.polinalinen.madre.sourdough.StarterName
import com.polinalinen.madre.ui.components.BackLabel
import com.polinalinen.madre.ui.components.BookButton
import com.polinalinen.madre.ui.components.BubbleVignette
import com.polinalinen.madre.ui.components.HairRule
import com.polinalinen.madre.ui.components.InkBlot
import com.polinalinen.madre.ui.components.InkBlotSpot
import com.polinalinen.madre.ui.components.PageLabel
import com.polinalinen.madre.ui.components.breathingPage
import com.polinalinen.madre.ui.components.lightPage
import com.polinalinen.madre.ui.theme.AppColors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Закваска — «Дневник культуры» (DESIGN-V4.md, экран 4).
 * Механики: #1 дневник от первого лица, #4 формуляр, #5 дышащая страница.
 */
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
) {
    val colors = AppColors.current
    // «Клякса» — DESIGN-V4.md Cycle 3, фича InkBlot. Событие для seed — самое
    // свежее кормление (id), т.к. отдельного id у DiaryEntry/фазы нет.
    val inkEventId = history.firstOrNull()?.id ?: 0L

    // history[0] — текущее кормление, оно уже показано выше как «Мадре пишет».
    // Архив прошлых глав — всё остальное, и это единственный список экрана,
    // который растёт без потолка: он и едет в LazyColumn как настоящие items.
    val pastFeedings = history.drop(1)
    var expandedArchiveId by rememberSaveable { mutableStateOf<Long?>(null) }

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
            }

            // Дневник — рукописный шрифт, вертикальная линия полей слева.
            // Поверх — кляксы (InkBlot, Cycle 3): отменённая выпечка и голодная
            // фаза оставляют детерминированный след прямо на странице дневника.
            // Записей всегда единицы (их порождает MadreVoice из одной фазы),
            // поэтому это один item, а не items.
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

            // «Прошлые главы» (Cycle 1, DiaryArchive) — единственный список без
            // потолка: одна строка на каждое прошлое кормление за всю жизнь
            // закваски. Ключ — id записи, поэтому раскрытая глава остаётся
            // раскрытой, когда сверху добавляется новое кормление.
            if (pastFeedings.isNotEmpty()) {
                item { DiaryArchiveHeader() }
                itemsIndexed(pastFeedings, key = { _, feeding -> feeding.id }) { i, feeding ->
                    DiaryArchiveRow(
                        feeding = feeding,
                        // «Следующее» кормление, которым закончилась эта глава, —
                        // на один индекс ближе к настоящему в исходном списке.
                        nextFeedingMillis = history[i].timestampMillis,
                        profile = profile,
                        expanded = expandedArchiveId == feeding.id,
                        onToggle = {
                            expandedArchiveId = if (expandedArchiveId == feeding.id) null else feeding.id
                        },
                    )
                }
            }

            item {
            BubbleVignette(phase, Modifier.padding(horizontal = 22.dp, vertical = 8.dp))

            // Формуляр выпечки — механика #4. Он сознательно ограничен десятью
            // последними строками, поэтому живёт одним item: разбивать его на
            // items незачем, а клякса поверх требует общего Box.
            PageLabel("Формуляр кормлений", Modifier.padding(start = 22.dp, top = 10.dp), color = colors.espresso)
            Box(Modifier.padding(horizontal = 22.dp, vertical = 8.dp)) {
            Column(
                Modifier
                    .drawBehind { drawRect(colors.cream) }
                    .padding(12.dp)
            ) {
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                    FormHeader("дата", 1.2f)
                    FormHeader("мука/вода", 2f)
                    FormHeader("заметка", 1.5f, alignEnd = true)
                }
                Box(Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().padding(0.dp)) {
                        drawRect(colors.espresso, size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx()))
                    }
                }
                history.take(10).forEach { feeding ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.Bottom) {
                        Text(
                            romanDate(feeding.timestampMillis),
                            color = colors.cocoa, fontFamily = FontFamily.Serif, fontSize = 12.sp,
                            modifier = Modifier.weight(1.2f),
                        )
                        Text(
                            "${feeding.flourGrams}г · ${feeding.waterGrams}г",
                            color = colors.espresso, fontFamily = FontFamily.Serif, fontSize = 12.sp,
                            modifier = Modifier.weight(2f),
                        )
                        Text(
                            feeding.notes ?: "—",
                            color = colors.sage, fontFamily = FontFamily.Cursive, fontSize = 13.sp,
                            modifier = Modifier.weight(1.5f),
                            textAlign = androidx.compose.ui.text.style.TextAlign.End,
                            maxLines = 1,
                        )
                    }
                    HairRule()
                }
            }
                // Та же отменённая выпечка — вторая, отдельная клякса в формуляре
                // (другой offset seed'а — форма не совпадает с той, что в дневнике).
                if (cancelledBakeCount > 0) {
                    InkBlotSpot(
                        seed = InkBlot.seedFor(inkEventId, FORMULARY_BLOT_OFFSET + cancelledBakeCount),
                        color = colors.cocoa,
                        modifier = Modifier.align(Alignment.TopEnd).size(32.dp),
                    )
                }
            }

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
            }
        }
        }
    }
}

/**
 * Заголовок «Прошлых глав» (DESIGN-V4.md Cycle 1, фича DiaryArchive) —
 * оглавление второго тома. Отдельный item списка: строки архива ниже едут
 * как настоящие items с ключами, и общего Column у них больше нет.
 */
@Composable
private fun DiaryArchiveHeader(modifier: Modifier = Modifier) {
    val colors = AppColors.current
    Row(
        modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HairRule(Modifier.weight(1f))
        PageLabel("Прошлые главы", color = colors.espresso, modifier = Modifier.padding(horizontal = 10.dp))
        HairRule(Modifier.weight(1f))
    }
}

/**
 * Одна прошлая глава: дата римскими + первая строка записи, тап разворачивает,
 * чем эта глава закончилась ([nextFeedingMillis] — кормление, которое её
 * оборвало).
 */
@Composable
private fun DiaryArchiveRow(
    feeding: FeedingEntity,
    nextFeedingMillis: Long,
    profile: SourdoughProfile,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = AppColors.current
    // Своя рука на прошлую главу — FamilyHand, fallback-ключ = id кормления
    // (у FeedingEntity нет userId, но каждая запись всё же выглядит по-своему).
    val hand = FamilyHand.forUser(null, feeding.id).style()

    Column(modifier.fillMaxWidth().padding(horizontal = 22.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .clickable { onToggle() }
                .padding(vertical = 8.dp)
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    romanDate(feeding.timestampMillis),
                    color = colors.cocoa,
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 11.sp,
                    letterSpacing = 1.sp,
                    modifier = Modifier.width(56.dp).padding(top = 3.dp),
                )
                Text(
                    MadreVoice.archiveFirstLine(feeding),
                    color = hand.ink,
                    fontFamily = FontFamily.Cursive,
                    fontWeight = hand.fontWeight,
                    fontSize = 16.sp,
                    maxLines = if (expanded) Int.MAX_VALUE else 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).rotate(hand.rotationDeg),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Text(
                    MadreVoice.archiveFate(feeding, nextFeedingMillis, profile),
                    color = colors.sage,
                    fontFamily = FontFamily.Cursive,
                    fontSize = 15.sp,
                    lineHeight = 21.sp,
                    modifier = Modifier.padding(start = 56.dp, top = 3.dp),
                )
            }
        }
        HairRule()
    }
}

// Разные offset'ы у одного и того же inkEventId — клякса в дневнике и клякса
// в формуляре не совпадают по форме, хоть и рождены одним событием (InkBlot).
private const val HUNGRY_BLOT_OFFSET = 97
private const val FORMULARY_BLOT_OFFSET = 211

@Composable
private fun androidx.compose.foundation.layout.RowScope.FormHeader(text: String, weight: Float, alignEnd: Boolean = false) {
    Text(
        text.uppercase(),
        color = AppColors.current.cocoa,
        fontFamily = FontFamily.SansSerif,
        fontSize = 9.sp,
        letterSpacing = 1.sp,
        modifier = Modifier.weight(weight),
        textAlign = if (alignEnd) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start,
    )
}

/** «18.VII» — день + месяц римскими, как в библиотечном формуляре. */
private fun romanDate(millis: Long): String {
    val roman = listOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII")
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = millis }
    return "${cal.get(java.util.Calendar.DAY_OF_MONTH)}.${roman[cal.get(java.util.Calendar.MONTH)]}"
}
