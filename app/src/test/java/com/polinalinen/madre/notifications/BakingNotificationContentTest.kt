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
        starterName: String = "Соня",
    ) = BakingProgress(
        sessionId = 3L,
        recipeName = "Бородинский",
        starterName = starterName,
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

    /**
     * Cycle 20: ноль остаётся нулём и на паузе. «Пауза, осталось 0:00» — это
     * цифра ни о чём: шаг кончился, и пауза этого не отменяет. В ту же секунду
     * страница таймера говорит «время вышло», и двух ответов на одно событие в
     * книге быть не должно.
     */
    @Test
    fun `a bake paused past the end says the time is out, not zero zero`() {
        val done = content(progress(remainingSeconds = 0, isPaused = true))
        assertThat(done.timerText).isEqualTo("время вышло")
        assertThat(done.compact).contains("время вышло")
        assertThat(done.compact).doesNotContain("0:00")
        assertThat(done.bigText).doesNotContain("0:00")
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
    // ————— Cycle 19: своя карточка в развёрнутой шторке —————

    /**
     * Шапка своей карточки — имя ЗАКВАСКИ и название рецепта. «Мадре · в печи»
     * было бы неправдой у всех, кто закваску переназвал: имя лежит в
     * sourdough_configs, и колофон, дневник и шторка обязаны звать её одинаково.
     */
    @Test
    fun `the card header names the starter and the bread, in that order`() {
        assertThat(content().headerLine).isEqualTo("Соня · Бородинский")
    }

    @Test
    fun `the system title stays the bread alone, so the collapsed row is not doubled`() {
        assertThat(content().title).isEqualTo("Бородинский")
    }

    @Test
    fun `the card says the step and its number on their own line`() {
        assertThat(content().stepLine).isEqualTo("Расстойка · шаг 3 из 8")
    }

    /** Крупные цифры карточки — те же, что на странице таймера. */
    @Test
    fun `the big timer shows the same digits the timer page shows`() {
        assertThat(content().timerText).isEqualTo("1:02:05")
        assertThat(content(progress(remainingSeconds = 0)).timerText).isEqualTo("время вышло")
    }

    /**
     * «Скоро» — ярлык, а не второе число: пять минут до конца шага человек и
     * так видит по хронометру, но заметить их он должен, не вчитываясь.
     */
    @Test
    fun `the last five minutes get a badge, and it is a word, not an emoji`() {
        val soon = content(progress(remainingSeconds = 299))
        assertThat(soon.isUrgent).isTrue()
        assertThat(soon.badge).isEqualTo("скоро")
        assertThat(soon.compact).contains("скоро")
    }

    @Test
    fun `five minutes and one second is not yet soon`() {
        val calm = content(progress(remainingSeconds = 300))
        assertThat(calm.isUrgent).isFalse()
        assertThat(calm.badge).isNull()
        assertThat(calm.compact).doesNotContain("скоро")
    }

    /**
     * На паузе ничего не «скоро»: отсчёт стоит, и торопить человека нечем.
     * Ярлык говорит ровно то, что происходит.
     */
    @Test
    fun `a paused bake is never urgent, however little is left`() {
        val paused = content(progress(remainingSeconds = 30, isPaused = true))
        assertThat(paused.isUrgent).isFalse()
        assertThat(paused.badge).isEqualTo("пауза")
    }

    @Test
    fun `a step that ran out is not urgent either — there is nothing left to hurry`() {
        val done = content(progress(remainingSeconds = 0))
        assertThat(done.isUrgent).isFalse()
        assertThat(done.badge).isNull()
    }

    /**
     * В карточке следующий шаг отбит средней точкой, как всё остальное на ней,
     * и по-прежнему без своего времени: один ход выпечки — один отсчёт.
     */
    @Test
    fun `the card names the next step with a middle dot and no second countdown`() {
        assertThat(content().nextLine).isEqualTo("дальше · Формовка")
        assertThat(content().nextLine).doesNotContain(":")
    }

    @Test
    fun `the card calls the last step the last one`() {
        val last = content(progress(stepIndex = 7, stepCount = 8, nextStepTitle = null))
        assertThat(last.nextLine).isEqualTo("последний шаг")
    }

    /**
     * Крупные цифры TalkBack читает по знаку — «один двоеточие ноль два». Для
     * него у карточки есть та же секунда словами, что уже проверена на экране.
     */
    @Test
    fun `the big timer carries a spoken label for the screen reader`() {
        assertThat(content().spokenTimer).isEqualTo("осталось 1 час 2 минуты")
        assertThat(content(progress(isPaused = true)).spokenTimer).startsWith("пауза")
    }

    /**
     * Своя карточка есть не на всякой прошивке. BigText остаётся полным — это
     * не запасной путь «на всякий случай», а то, что человек прочитает, если
     * RemoteViews ему не покажут.
     */
    @Test
    fun `the big text stays complete, because the custom card is not guaranteed`() {
        val big = content().bigText
        assertThat(big).contains("Расстойка")
        assertThat(big).contains("осталось 1:02:05")
        assertThat(big).contains("Формовка")
    }

    @Test
    fun `every bake gets its own pending intent, and none of them is the feeding one`() {
        val codes = (1L..50L).map { BakingNotificationContent.intentRequestCode(it) }
        assertThat(codes).containsNoDuplicates()
        assertThat(codes).doesNotContain(1001)
    }
}
