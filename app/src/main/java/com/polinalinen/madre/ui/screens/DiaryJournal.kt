package com.polinalinen.madre.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.polinalinen.madre.data.db.entities.FeedingEntity
import com.polinalinen.madre.data.db.entities.StorageLocation
import com.polinalinen.madre.model.RuShortDate
import com.polinalinen.madre.ui.components.AgedPhoto
import com.polinalinen.madre.ui.components.HairRule
import com.polinalinen.madre.ui.components.bookAction
import com.polinalinen.madre.ui.kit.*
import com.polinalinen.madre.utils.PhotoStore
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

internal data class JournalWeek(val start: LocalDate, val label: String, val entries: List<FeedingEntity>)

/** Presentation only: Monday-local calendar boundaries, never reorder the hero's input. */
internal fun journalWeeks(history: List<FeedingEntity>, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<JournalWeek> {
    fun monday(millis: Long) = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate()
        .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val current = monday(now)
    fun dated(date: LocalDate) = date.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale("ru")))
    return history.groupBy { monday(it.timestampMillis) }.toSortedMap(reverseOrder()).map { (start, rows) ->
        JournalWeek(start, when (start) {
            current -> "Эта неделя"
            current.minusWeeks(1) -> "Прошлая неделя"
            else -> "${dated(start)} — ${dated(start.plusDays(6))}"
        }, rows)
    }
}

/** All persisted facts remain verbatim; a row tap only changes local disclosure state. */
@Composable
internal fun JournalRow(feeding: FeedingEntity, currentYear: Int) {
    val colors = LocalPaper.current
    // Inputs only reset a live composition; the keyed group also scopes restored bytes.
    var expanded by key(feeding.sourdoughConfigId, feeding.id) {
        rememberSaveable { mutableStateOf(false) }
    }
    val hydration = feeding.finalHydrationPercent ?: feeding.hydrationPercent
    val storage = if (feeding.storageLocation == StorageLocation.FRIDGE) "холодильник" else "кухня"
    val spokenDate = RuShortDate.accessible(feeding.timestampMillis)
    val spoken = buildString {
        append(spokenDate)
        append(". Закваска: ")
        append(feeding.retainedStarterGrams?.let { "$it граммов" } ?: "не указана")
        append(". Мука: ${feeding.flourGrams} граммов")
        append(". Вода: ${feeding.waterGrams} граммов")
        append(". Гидратация: ")
        append(hydration?.let { "$it процентов" } ?: "не указана")
        append(". Хранение: $storage.")
    }
    Column(Modifier.fillMaxWidth().testTag("diary-entry-${feeding.id}")) {
        Column(Modifier.fillMaxWidth()
            .then(bookAction(if (expanded) "Свернуть подробности кормления $spokenDate"
                else "Показать подробности кормления $spokenDate", repeatable = true) { expanded = !expanded })
            .clearAndSetSemantics {
                contentDescription = spoken
                stateDescription = if (expanded) "Подробности раскрыты" else "Подробности свёрнуты"
            }
            .padding(horizontal = 22.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
                Text(RuShortDate.visible(feeding.timestampMillis, currentYear),
                    Modifier.weight(1f).testTag("journal-date-${feeding.id}"), color = colors.ink,
                    style = PaperType.ui.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Light))
                feeding.photoPath?.let { JournalThumbnail(it, feeding.id) }
            }
            Text("${feeding.retainedStarterGrams?.toString() ?: "—"} · ${feeding.flourGrams} · ${feeding.waterGrams} г",
                Modifier.fillMaxWidth().testTag("journal-masses-${feeding.id}"), color = colors.ink,
                style = PaperType.ui.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold))
            Text("${spokenDate.substringAfter(", ")} · $storage", Modifier.fillMaxWidth().testTag("journal-time-${feeding.id}"),
                color = colors.ink2, style = PaperType.caption)
            Text(hydration?.let { "$it%" } ?: "Гидратация не указана",
                Modifier.fillMaxWidth().testTag("journal-hydration-${feeding.id}"), color = colors.acc,
                style = PaperType.ui.copy(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Normal))
            if (!expanded) feeding.notes?.let {
                Text(it, Modifier.fillMaxWidth().testTag("journal-note-${feeding.id}"), color = colors.ink, style = PaperType.note)
            }
            Text(if (expanded) "Свернуть" else "Подробнее", Modifier.fillMaxWidth(), color = colors.ink2, style = PaperType.caption)
        }
        if (expanded) {
            Column(Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                (feeding.generatedComment ?: feeding.starterObservation)?.let {
                    Text(it, Modifier.fillMaxWidth(), color = colors.ink2, style = PaperType.body.copy(fontSize = 13.sp, lineHeight = 19.sp))
                }
                feeding.notes?.let {
                    Text("Заметка: $it", Modifier.fillMaxWidth(), color = colors.ink, style = PaperType.note)
                }
                feeding.photoPath?.let { path ->
                    if (PhotoStore.isReadable(LocalContext.current, path)) {
                        AgedPhoto(photoPath = path, takenAtMillis = feeding.timestampMillis, height = 140.dp,
                            caption = "Фотокарточка кормления")
                    } else {
                        Text("Фото: файл не найден", Modifier.fillMaxWidth(), color = colors.ink2, style = PaperType.caption)
                    }
                }
            }
        }
        HairRule()
    }
}

/** 40dp local image, explicitly bounded decode; no full-size request before disclosure. */
@Composable
private fun JournalThumbnail(path: String, id: Long) {
    val context = LocalContext.current
    val pixels = with(LocalDensity.current) { 40.dp.roundToPx() }
    if (PhotoStore.isReadable(context, path)) {
        AsyncImage(model = remember(path, pixels) { journalThumbnailRequest(context, path, pixels) },
            contentDescription = "Миниатюра кормления", contentScale = ContentScale.Crop,
            modifier = Modifier.size(40.dp).testTag("journal-thumbnail-$id"))
    } else {
        // Text is deliberately outside the 40dp slot: large-font users must be able to read it.
        Text("Фото: файл не найден", Modifier.widthIn(max = 120.dp).testTag("journal-missing-photo-$id"),
            color = LocalPaper.current.ink2, style = PaperType.caption)
    }
}

internal fun journalThumbnailRequest(context: android.content.Context, path: String, pixels: Int): ImageRequest =
    ImageRequest.Builder(context).data(PhotoStore.resolve(context, path)).size(pixels).crossfade(false).build()
