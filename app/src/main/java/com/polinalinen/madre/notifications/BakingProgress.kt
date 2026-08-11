package com.polinalinen.madre.notifications

import com.polinalinen.madre.sourdough.StarterName
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Cycle 12: слепок хода одной выпечки — ровно то, что видно в шторке.
 *
 * Отдельный от [com.polinalinen.madre.model.BakingSession] тип не роскошь:
 * уведомление рисует сервис, у которого нет ни рецепта, ни ViewModel, и
 * тащить туда всю модель ради названия и остатка было бы лишним. Заодно всё,
 * что попадает в строку и в полоску, считается чистыми функциями и проверяется
 * юнит-тестом — прогресс обязан быть настоящим.
 */
data class BakingProgress(
    val sessionId: Long,
    val recipeName: String,
    /**
     * Cycle 19: как зовут закваску — для шапки карточки в шторке.
     *
     * Едет тем же слепком, что и всё остальное, и берётся из тех же
     * sourdough_configs, что читает колофон. Своего источника у сервиса нет
     * намеренно: два места, помнящие одно имя, рано или поздно разойдутся в
     * его написании.
     */
    val starterName: String = StarterName.DEFAULT,
    val stepTitle: String,
    /** Номер текущего шага с нуля. */
    val stepIndex: Int,
    val stepCount: Int,
    /** Сколько осталось до конца текущего шага. */
    val remainingSeconds: Long,
    /** Сколько прошло от начала всей выпечки по плану. */
    val elapsedSeconds: Long,
    /** Вся выпечка по плану. */
    val totalSeconds: Long,
    val isPaused: Boolean,
    /** Название шага, который начнётся после текущего; null on the last step. */
    val nextStepTitle: String? = null,
) {

    /**
     * Доля пройденного по ВРЕМЕНИ, а не по номеру шага: восемь шагов, из
     * которых один длится три часа, а семь по пять минут — это не «12% за
     * шаг». Полоска в шторке должна двигаться так же, как идёт выпечка.
     */
    fun permille(): Int {
        if (totalSeconds <= 0L) return 0
        val raw = elapsedSeconds * PROGRESS_MAX / totalSeconds
        return raw.coerceIn(0L, PROGRESS_MAX.toLong()).toInt()
    }

    /** «Расстойка · шаг 3 из 8 · осталось 1:02:05» */
    fun contentText(): String {
        val tail = if (isPaused) "пауза" else "осталось ${formatRemaining(remainingSeconds)}"
        return "$stepTitle · шаг ${stepIndex + 1} из $stepCount · $tail"
    }

    /**
     * Cycle 14: следующий шаг — только название.
     *
     * До этого цикла здесь стояло «Следующий шаг: Формовка · через 2:05» — со
     * своим временем, которое на деле было временем ТЕКУЩЕГО шага. Экран и
     * шторка показывали одну и ту же секунду дважды, и понять, до чего именно
     * 2:05, было невозможно. Один ход выпечки — один отсчёт; здесь его нет.
     */
    fun nextStepText(): String = BakingProgressFormatter.nextStepText(
        stepIndex = stepIndex,
        stepCount = stepCount,
        nextStepTitle = nextStepTitle,
    )

    /** То же для своей карточки в шторке — со средней точкой. */
    fun shadeNextLine(): String = BakingProgressFormatter.shadeNextLine(
        stepIndex = stepIndex,
        stepCount = stepCount,
        nextStepTitle = nextStepTitle,
    )

    companion object {
        /** Полоска в NotificationCompat.setProgress — в тысячных. */
        const val PROGRESS_MAX = 1000

        /**
         * Свой id уведомления на каждую выпечку: одновременно в печи может
         * быть несколько, и строка одной не должна затирать другую. Диапазон
         * заведомо не пересекается с напоминанием о кормлении (1001).
         */
        private const val ID_BASE = 2000

        fun notificationId(sessionId: Long): Int = ID_BASE + sessionId.toInt()

        /**
         * Остаток словами — для экранного диктора.
         *
         * Крупные цифры «1:02:05» TalkBack читает как «один двоеточие ноль два
         * двоеточие ноль пять»: это и есть весь таймер выпечки, и незрячий
         * человек остаётся с ним один на один. Здесь то же самое время сказано
         * по-русски, целыми словами, и склонение считается честно — «21 минута»,
         * но «11 минут».
         *
         * Секунды называются только когда часов нет: «два часа три минуты сорок
         * одна секунда» — это не ответ на вопрос «сколько ещё».
         */
        fun timerLabel(remainingSeconds: Long, isPaused: Boolean): String {
            val time = spokenRemaining(remainingSeconds)
            return when {
                remainingSeconds <= 0L -> if (isPaused) "пауза, время вышло" else "время вышло"
                isPaused -> "пауза, осталось $time"
                else -> "осталось $time"
            }
        }

        /** «1 час 2 минуты», «45 секунд». Ноль здесь — «время вышло». */
        fun spokenRemaining(totalSeconds: Long): String {
            if (totalSeconds <= 0L) return "время вышло"
            val h = totalSeconds / 3600
            val m = (totalSeconds % 3600) / 60
            val s = totalSeconds % 60
            val parts = buildList {
                if (h > 0) add("$h ${hourWord(h)}")
                if (m > 0) add("$m ${minuteWord(m)}")
                // Секунды — только в последний час, иначе они лишний шум.
                if (h == 0L && (s > 0 || m == 0L)) add("$s ${secondWord(s)}")
            }
            return parts.joinToString(" ")
        }

        private fun hourWord(n: Long) = plural(n, "час", "часа", "часов")

        private fun minuteWord(n: Long) = plural(n, "минута", "минуты", "минут")

        private fun secondWord(n: Long) = plural(n, "секунда", "секунды", "секунд")

        /** Русское склонение по числу: 21 минута, 22 минуты, 11 минут. */
        private fun plural(n: Long, one: String, few: String, many: String) = when {
            n % 10 == 1L && n % 100 != 11L -> one
            n % 10 in 2L..4L && n % 100 !in 12L..14L -> few
            else -> many
        }

        /** hh:mm:ss при часах, иначе m:ss — тот же формат, что на экране таймера. */
        fun formatRemaining(totalSeconds: Long): String {
            val safe = totalSeconds.coerceAtLeast(0)
            val h = safe / 3600
            val m = (safe % 3600) / 60
            val s = safe % 60
            return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
        }
    }
}

/**
 * Pure, UI-independent wording for the step after the current one.
 *
 * Времени сюда не передают вовсе — и это не упущение, а условие: у следующего
 * шага не может быть своего отсчёта, пока идёт текущий.
 */
object BakingProgressFormatter {

    /** Строка BigText и страницы таймера: «дальше: Формовка». */
    fun nextStepText(
        stepIndex: Int,
        stepCount: Int,
        nextStepTitle: String?,
    ): String = withPrefix("дальше: ", stepIndex, stepCount, nextStepTitle)

    /**
     * Cycle 19: та же мысль в своей карточке шторки, отбитая средней точкой —
     * как шаг, ярлык и всё прочее на ней. Двоеточие там было бы единственным
     * во всей карточке и читалось бы как начало списка.
     */
    fun shadeNextLine(
        stepIndex: Int,
        stepCount: Int,
        nextStepTitle: String?,
    ): String = withPrefix("дальше · ", stepIndex, stepCount, nextStepTitle)

    private fun withPrefix(
        prefix: String,
        stepIndex: Int,
        stepCount: Int,
        nextStepTitle: String?,
    ): String = if (stepIndex >= stepCount - 1 || nextStepTitle.isNullOrBlank()) {
        LAST_STEP
    } else {
        "$prefix$nextStepTitle"
    }

    private const val LAST_STEP = "последний шаг"
}

/**
 * Что сейчас в печи — на весь процесс приложения.
 *
 * Живёт в MadreApplication, потому что читателей двое и они не видят друг
 * друга: BakingViewModel (пишет на каждом тике таймера) и
 * [BakingProgressService] (рисует шторку). Пустой список — значит выпечек нет,
 * и сервис обязан уйти вместе со своими уведомлениями.
 */
class ActiveBakes {

    private val _progress = MutableStateFlow<List<BakingProgress>>(emptyList())
    val progress: StateFlow<List<BakingProgress>> = _progress.asStateFlow()

    fun publish(all: List<BakingProgress>) {
        _progress.value = all
    }

    fun clear() {
        _progress.value = emptyList()
    }
}
