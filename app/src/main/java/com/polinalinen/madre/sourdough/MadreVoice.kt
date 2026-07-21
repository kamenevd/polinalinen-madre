package com.polinalinen.madre.sourdough

import com.polinalinen.madre.data.db.entities.FeedingEntity
import java.util.Calendar

/**
 * «Мадре пишет» — генератор дневниковых записей от первого лица.
 * Данные те же, что были бы на дашборде (фазовая модель SourdoughProfile),
 * просто озвучены голосом закваски. См. DESIGN-V4.md, механика #1.
 */
data class DiaryEntry(
    val timeLabel: String,      // "06:12"
    val text: String,
    val isHighlight: Boolean,   // пик/голод — выделяются
)

object MadreVoice {

    fun entriesFor(
        lastFeeding: FeedingEntity?,
        profile: SourdoughProfile,
        nowMillis: Long = System.currentTimeMillis(),
    ): List<DiaryEntry> {
        if (lastFeeding == null) {
            return listOf(DiaryEntry("—", "меня ещё не кормили в этом дневнике. начнём?", false))
        }
        val entries = mutableListOf<DiaryEntry>()
        val fedAt = lastFeeding.timestampMillis
        entries += DiaryEntry(timeOf(fedAt), feedingLine(lastFeeding), false)

        val hours = (nowMillis - fedAt) / 3_600_000f
        val peak = profile.peakHours

        // Прошли ли ключевые точки цикла — дописываем строчки по мере жизни
        if (hours > peak * 0.25f) {
            entries += DiaryEntry(
                timeOf(fedAt + (peak * 0.25f * 3_600_000).toLong()),
                "расту. пузырьки — это я дышу",
                false,
            )
        }
        when (currentPhase(hours, profile)) {
            GrowthPhase.PEAK -> entries += DiaryEntry(
                timeOf(nowMillis), "я на пике! пеки со мной сейчас", true,
            )
            GrowthPhase.DECLINING -> entries += DiaryEntry(
                timeOf(nowMillis), "устала, опадаю. в следующий раз лови меня раньше", false,
            )
            GrowthPhase.HUNGRY -> entries += DiaryEntry(
                timeOf(nowMillis), "я проголодалась. покорми меня, пожалуйста", true,
            )
            else -> {}
        }
        return entries
    }

    /** Короткий статус для первой полосы (главная): последняя строчка дневника. */
    fun headline(lastFeeding: FeedingEntity?, profile: SourdoughProfile): String {
        if (lastFeeding == null) return "меня ещё не кормили…"
        val hours = hoursSinceFeeding(lastFeeding.timestampMillis)
        return when (currentPhase(hours, profile)) {
            GrowthPhase.LAG -> "проснулась, потягиваюсь"
            GrowthPhase.GROWING -> "расту. загляни через ${formatHourOffset(profile.peakHours - hours)}"
            GrowthPhase.PEAK -> "я на пике! пеки со мной сейчас"
            GrowthPhase.DECLINING -> "отдыхаю после пика"
            GrowthPhase.HUNGRY -> "я проголодалась!"
            GrowthPhase.EMPTY -> "жду первого кормления"
        }
    }

    private fun feedingLine(feeding: FeedingEntity): String {
        val place = when (feeding.storageLocation) {
            com.polinalinen.madre.data.db.entities.StorageLocation.FRIDGE -> "теперь дремлю в холодильнике"
            else -> "стою на кухне, тепло"
        }
        return "меня покормили: ${feeding.flourGrams}г муки, ${feeding.waterGrams}г воды. $place"
    }

    private fun timeOf(millis: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        return "%d:%02d".format(h, m)
    }
}
