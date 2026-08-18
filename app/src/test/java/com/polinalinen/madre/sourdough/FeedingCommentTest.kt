package com.polinalinen.madre.sourdough

import com.google.common.truth.Truth.assertThat
import java.util.Calendar
import java.util.TimeZone
import org.junit.Test

/**
 * «Комментарий закваски» — снимок фактов, а не рассказ. Здесь проверяется
 * ровно то, чего в нём быть не должно: выдуманной погоды и выдуманного
 * сработавшего уведомления.
 */
class FeedingCommentTest {

    private val moscow = TimeZone.getTimeZone("Europe/Moscow")

    private fun momentOf(day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance(moscow).apply {
            clear()
            set(2026, Calendar.AUGUST, day, hour, minute, 0)
        }.timeInMillis

    private fun facts(
        savedAtMillis: Long = momentOf(19, 8, 30),
        previousFeedingMillis: Long? = momentOf(18, 8, 30),
        priorHydrationPercent: Int = 50,
        priorHydrationIsInitial: Boolean = false,
        weather: String? = null,
    ) = FeedingFacts(
        savedAtMillis = savedAtMillis,
        intervalHours = 24,
        previousFeedingMillis = previousFeedingMillis,
        previousFlourGrams = if (previousFeedingMillis == null) null else 100,
        previousWaterGrams = if (previousFeedingMillis == null) null else 50,
        priorHydrationPercent = priorHydrationPercent,
        priorHydrationIsInitial = priorHydrationIsInitial,
        retainedStarterGrams = 50,
        addedFlourGrams = 100,
        addedWaterGrams = 50,
        finalHydrationPercent = 50,
        weather = weather,
    )

    @Test fun weatherAppearsOnlyWhenItIsKnown() {
        val withWeather = FeedingComment.compose(facts(weather = "+18°, влажность 60%"), moscow)
        assertThat(withWeather).contains("Погода за окном: +18°, влажность 60%.")
    }

    /**
     * Нет разрешения на геолокацию, нет свежей точки, нет сети — о погоде не
     * говорится ничего. Ни «погода неизвестна», ни выдуманного градуса.
     */
    @Test fun missingWeatherLeavesNoWeatherClaim() {
        val text = FeedingComment.compose(facts(weather = null), moscow)
        assertThat(text).doesNotContain("Погода")
        assertThat(text).doesNotContain("погод")
        assertThat(text).doesNotContain("°")
        // И комментарий всё равно остаётся полноценным.
        assertThat(text).contains("Гидратация после кормления: 50%.")
        assertThat(text).contains("Оставили 50 г закваски, дали 100 г муки и 50 г воды.")
    }

    /** Про уведомление комментарий не говорит ничего: WorkManager мог опоздать. */
    @Test fun commentNeverClaimsANotificationFired() {
        val text = FeedingComment.compose(facts(), moscow)
        assertThat(text).doesNotContain("уведомл")
        assertThat(text).doesNotContain("напомин")
    }

    @Test fun onTimeFeedingNamesTheExactDueMoment() {
        val text = FeedingComment.compose(facts(savedAtMillis = momentOf(19, 8, 30)), moscow)
        assertThat(text).contains("Расписание: каждые 24 ч.")
        assertThat(text).contains("С прошлого кормления прошло 1 дн.")
        assertThat(text).contains("Срок был 19 августа, 08:30 — записано не позже срока.")
        assertThat(text).contains("В прошлый раз дали 100 г муки и 50 г воды.")
    }

    @Test fun lateFeedingNamesHowLateItWas() {
        val text = FeedingComment.compose(facts(savedAtMillis = momentOf(19, 10, 15)), moscow)
        assertThat(text).contains("Срок был 19 августа, 08:30 — записано позже срока на 1 ч 45 мин.")
    }

    @Test fun savedBeforePreviousRowKeepsFactualSeedAndOmitsPunctualityArtifacts() {
        val text = FeedingComment.compose(
            facts(
                savedAtMillis = momentOf(19, 8, 15),
                previousFeedingMillis = momentOf(19, 8, 30),
            ),
            moscow,
        )

        assertThat(text).contains("Записано 19 августа, 08:15.")
        assertThat(text).contains("Интервал между записями нельзя проверить из-за изменения времени на устройстве.")
        assertThat(text).contains("В прошлый раз дали 100 г муки и 50 г воды.")
        assertThat(text).contains("Оставили 50 г закваски, дали 100 г муки и 50 г воды.")
        assertThat(text).contains("Гидратация после кормления: 50%.")
        assertThat(text).doesNotContain("Срок был")
        assertThat(text).doesNotContain("меньше минуты")
        assertThat(text).doesNotContain("не позже срока")
        assertThat(text).doesNotContain("позже срока")
    }

    @Test fun saveTimeSubtractionOverflowKeepsSeededFactsAndOmitsFabricatedTiming() {
        val text = FeedingComment.compose(
            facts(
                savedAtMillis = Long.MAX_VALUE,
                previousFeedingMillis = Long.MIN_VALUE,
            ),
            moscow,
        )

        assertThat(text).contains("Записано")
        assertThat(text).contains("Интервал между записями нельзя проверить из-за изменения времени на устройстве.")
        assertThat(text).contains("В прошлый раз дали 100 г муки и 50 г воды.")
        assertThat(text).contains("Оставили 50 г закваски, дали 100 г муки и 50 г воды.")
        assertThat(text).contains("Гидратация после кормления: 50%.")
        assertThat(text).doesNotContain("Срок был")
        assertThat(text).doesNotContain("меньше минуты")
        assertThat(text).doesNotContain("не позже срока")
        assertThat(text).doesNotContain("позже срока")
    }

    /** Первое кормление называет источник стартовой гидратации вслух. */
    @Test fun firstFeedingDeclaresTheInitialHydrationSource() {
        val text = FeedingComment.compose(
            facts(previousFeedingMillis = null, priorHydrationIsInitial = true),
            moscow,
        )
        assertThat(text).contains("Прошлого кормления в дневнике нет — это первая запись.")
        assertThat(text).contains("Гидратация до кормления: 50% (стартовая гидратация 50%).")
        // Ни срока, ни опоздания: сравнивать не с чем.
        assertThat(text).doesNotContain("Срок был")
    }

    /** Дальше стартовая уже не поминается — гидратация приходит из дневника. */
    @Test fun laterFeedingsQuoteTheSavedHydration() {
        val text = FeedingComment.compose(facts(priorHydrationPercent = 72), moscow)
        assertThat(text).contains("Гидратация до кормления: 72%.")
        assertThat(text).doesNotContain("стартовая гидратация")
    }
}
