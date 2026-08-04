package com.polinalinen.madre.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polinalinen.madre.data.db.entities.BakeRecordEntity
import com.polinalinen.madre.model.Recipe
import com.polinalinen.madre.ui.components.AgedPhoto
import com.polinalinen.madre.ui.components.BackLabel
import com.polinalinen.madre.ui.components.BookButton
import com.polinalinen.madre.ui.components.BookButtonVariant
import com.polinalinen.madre.ui.components.HairRule
import com.polinalinen.madre.ui.components.HeavyRule
import com.polinalinen.madre.ui.components.PageLabel
import com.polinalinen.madre.ui.components.Stamp
import com.polinalinen.madre.ui.theme.AppColors
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Формуляр книги + ритм выпечки (хитмэп) + главы — один разворот (2026-07-21,
 * следом за просьбой добавить статистику на Полку). Всё считается из
 * [BakeRecordEntity] через BakeHistoryRepository — тех же записей, что видит
 * лайтбокс по главам, поэтому цифры формуляра/хитмэпа/глав не могут разойтись.
 *
 * Пока без бэка (см. Дима, 2026-07-21) реальные данные есть только для
 * собственной книги. Для книг друзей этот экран будет переиспользован один в
 * один, когда появится синхронизация — вопрос только источника [records].
 */
@Composable
fun BookStatsScreen(
    ownerLabel: String,
    isMe: Boolean,
    recipes: List<Recipe>,
    records: List<BakeRecordEntity>,
    onBack: () -> Unit,
) {
    val colors = AppColors.current
    var openedRecipe by remember { mutableStateOf<Recipe?>(null) }

    val dates = remember(records) {
        records.map { Instant.ofEpochMilli(it.completedAtMillis).atZone(ZoneId.systemDefault()).toLocalDate() }
    }
    val topRecipeName = remember(records) { topRecipe(records) }
    val avgInterval = remember(dates) { avgIntervalDays(dates) }
    val weekday = remember(dates) { if (dates.size >= 3) commonWeekday(dates) else null }
    val buckets = remember(dates) { weeklyHeat(dates, WEEKS) }
    val maxBucket = (buckets.maxOrNull() ?: 0).coerceAtLeast(1)

    // Главы едут строками по три: список рецептов задан книгой, но растёт с
    // каждым новым, и держать его целиком смонтированным (как делал прежний
    // LazyVerticalGrid с руками посчитанной высотой внутри verticalScroll) —
    // ровно то, чего LazyColumn и должен избегать.
    val chapterRows = remember(recipes) { recipes.chunked(CHAPTER_COLUMNS) }

    Surface(color = colors.paper, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.statusBarsPadding(),
            contentPadding = PaddingValues(bottom = 32.dp),
        ) {
            item {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackLabel("Полка", onClick = onBack)
                Stamp(if (isMe) "твоя книга" else "только чтение", colors.cocoa)
            }

            Column(Modifier.padding(horizontal = 22.dp)) {
                Text(
                    ownerLabel,
                    color = colors.espresso,
                    fontFamily = FontFamily.Serif,
                    fontWeight = FontWeight.Bold,
                    fontSize = 28.sp,
                )
                Text(
                    if (records.isEmpty()) "книга пока пуста" else "испечено раз: ${records.size}",
                    color = colors.cocoa,
                    fontFamily = FontFamily.Serif,
                    fontStyle = FontStyle.Italic,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }

            HeavyRule(Modifier.padding(horizontal = 22.dp, vertical = 14.dp))

            PageLabel("Формуляр книги", color = colors.espresso, modifier = Modifier.padding(horizontal = 22.dp))
            Column(Modifier.padding(horizontal = 22.dp, vertical = 10.dp).fillMaxWidth()) {
                FormularRow("Выпечек всего", "${records.size}")
                if (topRecipeName != null) FormularRow("Любимая глава", topRecipeName)
                FormularRow("Печёт", if (avgInterval != null) "раз в $avgInterval ${dayWord(avgInterval)}" else "рано считать")
            }

            PageLabel("Ритм выпечки", color = colors.espresso, modifier = Modifier.padding(horizontal = 22.dp, vertical = 8.dp))
            Row(Modifier.fillMaxWidth().padding(horizontal = 22.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                buckets.forEach { c ->
                    val t = c.toFloat() / maxBucket
                    val tone = when {
                        c == 0 -> colors.parchment
                        t > 0.66f -> colors.amberDeep
                        t > 0.33f -> colors.crust
                        else -> colors.caramel
                    }
                    Box(
                        Modifier
                            .weight(1f)
                            .aspectRatio(1f)
                            .drawBehind { drawRect(tone) }
                    )
                }
            }
            Text(
                weekday?.let { "чаще всего — по $it" } ?: if (dates.isEmpty()) "здесь пока тихо — ни одной записи" else "пока рано искать закономерность",
                color = colors.cocoa,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 22.dp).padding(top = 8.dp),
            )

            HairRule(Modifier.padding(horizontal = 22.dp, vertical = 16.dp))
            PageLabel("Главы", color = colors.espresso, modifier = Modifier.padding(horizontal = 22.dp))
            Spacer(Modifier.height(10.dp))
            }

            // Ключ — id первого рецепта строки: строки главы стабильны, пока
            // стабилен порядок книги, и перерисовываются только изменившиеся.
            items(chapterRows, key = { row -> row.first().id }) { row ->
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { r ->
                        val count = records.count { it.recipeId == r.id }
                        ChapterTile(
                            name = r.name,
                            count = count,
                            onClick = { if (count > 0) openedRecipe = r },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    // Хвост неполной строки — пустые места, чтобы последняя
                    // плитка не растянулась на всю ширину.
                    repeat(CHAPTER_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            item {
            Text(
                "страницы проявляются по мере того, как здесь пекут",
                color = colors.cocoa,
                fontFamily = FontFamily.Serif,
                fontStyle = FontStyle.Italic,
                fontSize = 11.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            )
            }
        }
    }

    val opened = openedRecipe
    if (opened != null) {
        val attempts = records.filter { it.recipeId == opened.id }.sortedByDescending { it.completedAtMillis }
        // Форма/цвет — не стандартный Material-попап (concept: "никакого material-дизайна",
        // DESIGN-V4.md §Концепция): плоская карточка страницы, скругление ≤4dp.
        AlertDialog(
            onDismissRequest = { openedRecipe = null },
            confirmButton = {
                // У лайтбокса не было ни одной кнопки закрытия: уйти можно
                // было только тапом мимо карточки или системной «назад».
                BookButton(
                    label = "Закрыть",
                    onClick = { openedRecipe = null },
                    variant = BookButtonVariant.SECONDARY,
                )
            },
            shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
            containerColor = colors.cream,
            titleContentColor = colors.espresso,
            textContentColor = colors.espresso,
            title = { Text(opened.name, fontFamily = FontFamily.Serif, fontWeight = FontWeight.Bold) },
            text = {
                // Попыток у любимой главы бывает много, и почти у каждой —
                // фотокарточка: список ленивый, чтобы лайтбокс не декодировал
                // разом все снимки за всю историю книги.
                LazyColumn(Modifier.heightIn(max = LIGHTBOX_MAX_HEIGHT)) {
                    items(attempts, key = { it.id }) { a ->
                        val d = Instant.ofEpochMilli(a.completedAtMillis).atZone(ZoneId.systemDefault()).toLocalDate()
                        Text("${formatRuDate(d)} · ×${a.portions}", fontFamily = FontFamily.Serif, fontSize = 14.sp)
                        // «Старое фото» (Cycle 6, AgedPhoto): вклеенная фотокарточка
                        // стареет вместе с записью — цвет, сепия, выцветание.
                        if (a.photoPath != null) {
                            AgedPhoto(
                                photoPath = a.photoPath,
                                takenAtMillis = a.completedAtMillis,
                                height = 110.dp,
                                caption = formatStatsPhotoCaption(opened.name, d),
                                modifier = Modifier.padding(vertical = 6.dp),
                            )
                        }
                    }
                }
            },
        )
    }
}

@Composable
private fun FormularRow(label: String, value: String) {
    val colors = AppColors.current
    Row(
        Modifier.fillMaxWidth().padding(vertical = 6.dp).drawBehind {
            drawLine(
                color = colors.flour,
                start = androidx.compose.ui.geometry.Offset(0f, size.height),
                end = androidx.compose.ui.geometry.Offset(size.width, size.height),
                strokeWidth = 1.dp.toPx(),
            )
        },
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, color = colors.cocoa, fontFamily = FontFamily.Serif, fontSize = 13.sp)
        Text(value, color = colors.sage, fontFamily = FontFamily.Serif, fontStyle = FontStyle.Italic, fontSize = 16.sp)
    }
}

@Composable
private fun ChapterTile(name: String, count: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = AppColors.current
    Column(modifier.clickable(enabled = count > 0, onClick = onClick)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .drawBehind {
                    if (count > 0) drawRect(colors.crust) else drawRect(colors.parchment)
                }
        ) {
            if (count > 0) {
                Text(
                    "×$count",
                    color = colors.cream,
                    fontFamily = FontFamily.SansSerif,
                    fontSize = 10.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
                )
            }
        }
        Text(
            name,
            color = if (count > 0) colors.espresso else colors.flour,
            fontFamily = FontFamily.Serif,
            fontStyle = FontStyle.Italic,
            fontSize = 12.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            maxLines = 2,
            modifier = Modifier.fillMaxWidth().padding(top = 3.dp),
        )
    }
}

private const val WEEKS = 12

/** Три главы в строке — как в прежней сетке разворота. */
private const val CHAPTER_COLUMNS = 3

/** Потолок лайтбокса: AlertDialog не даёт ленивому списку бесконечную высоту. */
private val LIGHTBOX_MAX_HEIGHT = 420.dp

private fun weeklyHeat(dates: List<LocalDate>, weeks: Int): List<Int> {
    val now = LocalDate.now()
    val weekStart = now.with(java.time.DayOfWeek.MONDAY)
    val buckets = IntArray(weeks)
    dates.forEach { d ->
        val diffDays = ChronoUnit.DAYS.between(d, weekStart)
        val idx = weeks - 1 - (diffDays / 7).toInt()
        if (idx in 0 until weeks) buckets[idx]++
    }
    return buckets.toList()
}

private fun avgIntervalDays(dates: List<LocalDate>): Int? {
    if (dates.size < 2) return null
    val sorted = dates.sorted()
    var total = 0L
    for (i in 1 until sorted.size) total += ChronoUnit.DAYS.between(sorted[i - 1], sorted[i])
    return Math.round(total.toDouble() / (sorted.size - 1)).toInt()
}

private fun commonWeekday(dates: List<LocalDate>): String? {
    val counts = IntArray(7)
    dates.forEach { counts[it.dayOfWeek.value - 1]++ }
    val order = counts.indices.sortedByDescending { counts[it] }
    if (counts[order[0]] == 0 || counts[order[0]] == counts[order[1]]) return null
    val names = listOf("понедельникам", "вторникам", "средам", "четвергам", "пятницам", "субботам", "воскресеньям")
    return names[order[0]]
}

private fun topRecipe(records: List<BakeRecordEntity>): String? =
    records.groupingBy { it.recipeId to it.recipeName }.eachCount().maxByOrNull { it.value }?.key?.second

private fun dayWord(n: Int) = when {
    n % 10 == 1 && n % 100 != 11 -> "день"
    n % 10 in 2..4 && n % 100 !in 12..14 -> "дня"
    else -> "дней"
}

private val RU_MONTHS = listOf(
    "января", "февраля", "марта", "апреля", "мая", "июня",
    "июля", "августа", "сентября", "октября", "ноября", "декабря",
)

internal fun formatStatsPhotoCaption(recipeName: String, date: LocalDate): String =
    "$recipeName, ${formatRuDate(date)}"

private fun formatRuDate(d: LocalDate) = "${d.dayOfMonth} ${RU_MONTHS[d.monthValue - 1]}"
