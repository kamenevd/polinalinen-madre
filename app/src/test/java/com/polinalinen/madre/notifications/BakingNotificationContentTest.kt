package com.polinalinen.madre.notifications

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Cycle 14: что именно человек читает в шторке, пока идёт выпечка.
 *
 * Строка собирается ЗДЕСЬ, чистой функцией над одним слепком хода — а не
 * внутри сервиса, где её нечем проверить. Android сам решает, разворачивать
 * ли карточку в шторке, и книга этого не обещает; она обещает другое: смысл
 * виден и в свёрнутом виде, обратный отсчёт виден всегда, а полный текст ждёт
 * в BigText без обрезания.
 */
class BakingNotificationContentTest {

    private val now = 1_700_000_000_000L

    private fun progress(
        stepIndex: Int = 2,
        stepCount: Int = 8,
        remainingSeconds: Long = 3_725,
        elapsedSeconds: Long = 900,
        totalSeconds: Long = 3_600,
        isPaused: Boolean = false,
        nextStepTitle: String? = "Формовка",
    ) = BakingProgress(
        sessionId = 3L,
        recipeName = "Бородинский",
        stepTitle = "Расстойка",
        stepIndex = stepIndex,
        stepCount = stepCount,
        remainingSeconds = remainingSeconds,
        elapsedSeconds = elapsedSeconds,
        totalSeconds = totalSeconds,
        isPaused = isPaused,
        nextStepTitle = nextStepTitle,
    )

    private fun content(progress: BakingProgress = progress()) =
        BakingNotificationContent.from(progress, now)

    @Test
    fun `the title is the bread, so the shade says what is baking`() {
        assertThat(content().title).isEqualTo("Бородинский")
    }

    @Test
    fun `the compact line leads with the step, not with a stray number`() {
        assertThat(content().compact).startsWith("Расстойка")
        assertThat(content().compact).contains("шаг 3 из 8")
    }

    /**
     * Живой отсчёт рисует система: setWhen + chronometer countDown тикают сами,
     * между обновлениями и в свёрнутой карточке. Дублировать его текстом в той
     * же строке — значит показать два разных числа в одной секунде.
     */
    @Test
    fun `a running bake hands the countdown to the system chronometer`() {
        val running = content()
        assertThat(running.usesChronometer).isTrue()
        assertThat(running.chronometerFinishAtMillis).isEqualTo(now + 3_725_000L)
    }

    @Test
    fun `the full text spells the time out, so nothing depends on the chronometer alone`() {
        assertThat(content().bigText).contains("осталось 1:02:05")
    }

    @Test
    fun `the full text names the next step and never counts it down separately`() {
        val big = content().bigText
        assertThat(big).contains("дальше: Формовка")
        // Один таймер на выпечку: у следующего шага нет своего отсчёта.
        assertThat(big.split("\n").filter { it.contains(":") && it.contains("осталось") }).hasSize(1)
    }

    @Test
    fun `the last step says it is the last one instead of naming a next`() {
        val last = content(progress(stepIndex = 7, stepCount = 8, nextStepTitle = null))
        assertThat(last.bigText).contains("последний шаг")
        assertThat(last.bigText).doesNotContain("дальше:")
    }

    @Test
    fun `a paused bake stops the chronometer and says it is paused`() {
        val paused = content(progress(isPaused = true))
        assertThat(paused.usesChronometer).isFalse()
        assertThat(paused.compact).contains("пауза")
        // Остаток всё равно назван — пауза не должна прятать, сколько осталось.
        assertThat(paused.compact).contains("1:02:05")
    }

    @Test
    fun `resuming brings the chronometer back with a fresh finish time`() {
        val paused = content(progress(isPaused = true))
        val resumed = content(progress(isPaused = false))
        assertThat(paused.usesChronometer).isFalse()
        assertThat(resumed.usesChronometer).isTrue()
        assertThat(resumed.chronometerFinishAtMillis).isGreaterThan(now)
    }

    /**
     * Ноль — не «отсчёт до сейчас»: обратный хронометр на нуле мигал бы
     * отрицательным временем. Книга просто говорит, что время вышло.
     */
    @Test
    fun `a step that ran out says so instead of counting to nothing`() {
        val done = content(progress(remainingSeconds = 0))
        assertThat(done.usesChronometer).isFalse()
        assertThat(done.compact).contains("время вышло")
        assertThat(done.bigText).contains("время вышло")
    }

    @Test
    fun `a step with no time at all never shows a phantom countdown`() {
        val instant = content(progress(remainingSeconds = 0, isPaused = false, stepIndex = 0))
        assertThat(instant.usesChronometer).isFalse()
        assertThat(instant.chronometerFinishAtMillis).isEqualTo(now)
    }

    @Test
    fun `the progress bar follows real time, straight from the snapshot`() {
        val bake = progress(elapsedSeconds = 900, totalSeconds = 3_600)
        assertThat(BakingNotificationContent.from(bake, now).progressPermille)
            .isEqualTo(bake.permille())
    }

    /** Свёрнутая карточка — одна строка: перевод строки в ней обрезал бы смысл. */
    @Test
    fun `the compact line is a single line`() {
        listOf(
            content(),
            content(progress(isPaused = true)),
            content(progress(remainingSeconds = 0)),
            content(progress(stepIndex = 7, stepCount = 8, nextStepTitle = null)),
        ).forEach { assertThat(it.compact).doesNotContain("\n") }
    }

    @Test
    fun `the full text is longer than the compact one — that is the point of it`() {
        assertThat(content().bigText.length).isGreaterThan(content().compact.length)
        assertThat(content().bigText).contains("\n")
    }

    /**
     * Тап ведёт в СВОЮ выпечку: у каждой сессии свой requestCode, иначе
     * PendingIntent'ы разных выпечек система считает одним и тем же, и вторая
     * строка в шторке открывает первую.
     */
    @Test
    fun `every bake gets its own pending intent, and none of them is the feeding one`() {
        val codes = (1L..50L).map { BakingNotificationContent.intentRequestCode(it) }
        assertThat(codes).containsNoDuplicates()
        assertThat(codes).doesNotContain(1001)
    }
}
