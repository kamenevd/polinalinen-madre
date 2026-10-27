package com.polinalinen.madre.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.polinalinen.madre.data.db.entities.FeedingEntity
import com.polinalinen.madre.data.db.entities.StorageLocation
import com.polinalinen.madre.sourdough.GrowthPhase
import com.polinalinen.madre.sourdough.StarterVoice
import com.polinalinen.madre.ui.kit.LocalPaper
import com.polinalinen.madre.ui.kit.PaperCard
import com.polinalinen.madre.ui.kit.PaperType

/** UI minutes only: never changes the domain clock, phase or feeding schedule. */
internal fun diaryElapsedLabel(lastFeedingMillis: Long?, nowMillis: Long): String? {
    if (lastFeedingMillis == null || lastFeedingMillis > nowMillis) return null
    val minutes = (nowMillis - lastFeedingMillis) / 60_000L
    return "${minutes / 60} ч ${minutes % 60} мин"
}

@Composable
internal fun DiaryHero(phase: GrowthPhase, history: List<FeedingEntity>, fallbackMillis: Long?, nowMillis: Long) {
    val p = LocalPaper.current
    // Room supplies insertion order (id DESC), not timestamp order, even after clock rollback.
    val latest = history.firstOrNull()
    val fedAt = latest?.timestampMillis ?: fallbackMillis
    val hydration = history.firstNotNullOfOrNull { it.finalHydrationPercent }
    val phaseColor = if (phase == GrowthPhase.PEAK) p.sage else p.acc
    val phaseLabel = when (phase) {
        GrowthPhase.EMPTY -> "Пока без кормлений"
        GrowthPhase.LAG -> "Лаг"
        GrowthPhase.GROWING -> "Рост"
        GrowthPhase.PEAK -> "Пик"
        GrowthPhase.DECLINING -> "Спад"
        GrowthPhase.HUNGRY -> "Голодна"
    }
    val timing = when {
        fedAt == null -> "Кормлений пока нет"
        fedAt > nowMillis -> "Время кормления позже текущего"
        else -> "после кормления"
    }
    val location = when (latest?.storageLocation) {
        StorageLocation.KITCHEN -> "кухня"
        StorageLocation.FRIDGE -> "холодильник"
        null -> null
    }
    val note = if (latest != null) {
        "$timing · ${latest.retainedStarterGrams ?: "—"}/${latest.flourGrams}/${latest.waterGrams} г · $location"
    } else if (fedAt != null) "$timing · массы и место не записаны" else timing

    PaperCard(Modifier.padding(horizontal = 22.dp, vertical = 12.dp).testTag("diary-hero")) {
        // Wrap rather than squeeze either fact at narrow widths / large font scales.
        FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.testTag("diary-phase-${phase.name}")
                .then(if (LocalDensity.current.fontScale > 1.3f) Modifier.fillMaxWidth() else Modifier)
                .background(phaseColor.copy(alpha = .12f), RoundedCornerShape(18.dp))
                .padding(horizontal = 12.dp, vertical = 6.dp), propagateMinConstraints = true) {
                Text(phaseLabel, style = PaperType.ui.copy(fontSize = 24.sp, lineHeight = 32.sp,
                    fontWeight = FontWeight.Normal), color = phaseColor)
            }
            Text("гидратация ${hydration?.let { "$it%" } ?: "—"}",
                style = PaperType.caption.copy(fontSize = 24.sp, lineHeight = 32.sp),
                color = p.acc, textAlign = TextAlign.End,
                modifier = Modifier.then(if (LocalDensity.current.fontScale > 1.3f) Modifier.fillMaxWidth() else Modifier)
                    .padding(vertical = 8.dp).testTag("diary-hydration"))
        }
        Text(diaryElapsedLabel(fedAt, nowMillis) ?: "—", color = p.ink,
            style = PaperType.number.copy(fontSize = 40.sp, lineHeight = 48.sp, fontWeight = FontWeight.ExtraLight),
            modifier = Modifier.padding(top = 14.dp).testTag("diary-elapsed"))
        Text(note, style = PaperType.caption, color = p.ink2,
            modifier = Modifier.padding(top = 4.dp).testTag("diary-feeding-note").semantics {
                if (latest != null) contentDescription = "$timing. Закваска: ${latest.retainedStarterGrams?.let { "$it г" } ?: "не указана"}. " +
                    "Мука: ${latest.flourGrams} г. Вода: ${latest.waterGrams} г. Хранение: $location."
            })
        Text("Мадре пишет · литературная реплика", style = PaperType.caption, color = p.ink2,
            modifier = Modifier.padding(top = 18.dp))
        // Editorial voice, not a saved observation or a claim about this jar. No new rotation/persistence.
        Text("«${StarterVoice.lineAt(20)}»", style = PaperType.note, color = p.ink,
            modifier = Modifier.padding(top = 6.dp).testTag("diary-voice"))
    }
}