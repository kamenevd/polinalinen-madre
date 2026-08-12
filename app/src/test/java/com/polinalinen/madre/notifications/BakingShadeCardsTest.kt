package com.polinalinen.madre.notifications

import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.R
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Cycle 21: карточки книги в шторке — разложенные на настоящих View.
 *
 * ЗАЧЕМ ЭТОТ ФАЙЛ. RemoteViews не компилируются вместе с разметкой: id,
 * которого в layout нет, и имя метода, которого у View нет, обе стороны
 * принимают молча, а расходятся они уже на телефоне — там, где увидеть это
 * может только Дима. Здесь карточка собирается той же функцией, что и в
 * сервисе, и прикладывается по-настоящему.
 *
 * Проверяется ровно то, ради чего цикл затевался: цифры в свёрнутой карточке
 * есть и они не отрицательные, а бумага и чернила стоят своими — иначе в
 * тёмной теме телефона книгу не прочитать.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [35])
class BakingShadeCardsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun progress(
        remainingSeconds: Long = 3_725,
        isPaused: Boolean = false,
    ) = BakingProgress(
        sessionId = 1L,
        recipeName = "Бородинский",
        starterName = "Соня",
        stepTitle = "Расстойка",
        stepIndex = 2,
        stepCount = 8,
        remainingSeconds = remainingSeconds,
        stepTotalSeconds = 7_200,
        isPaused = isPaused,
        nextStepTitle = "Формовка",
    )

    private fun compact(bake: BakingProgress = progress()): View =
        BakingShadeCards.compact(context, BakingNotificationContent.from(bake)).apply(context, null)

    private fun big(bake: BakingProgress = progress()): View =
        BakingShadeCards.big(context, BakingNotificationContent.from(bake)).apply(context, null)

    private fun View.text(id: Int): String = findViewById<TextView>(id).text.toString()

    private fun ink(colorRes: Int) = ContextCompat.getColor(context, colorRes)

    // ————— свёрнутая карточка —————

    @Test
    fun `the collapsed card carries the digits itself, since no chronometer runs them`() {
        val card = compact()
        assertThat(card.text(R.id.notif_compact_timer)).isEqualTo("1:02:05")
        assertThat(card.text(R.id.notif_compact_step)).isEqualTo("Расстойка · шаг 3 из 8")
    }

    @Test
    fun `a step that ran out says so in the collapsed card, with a full bar`() {
        val card = compact(progress(remainingSeconds = 0))
        assertThat(card.text(R.id.notif_compact_timer)).isEqualTo("время вышло")
        assertThat(card.findViewById<ProgressBar>(R.id.notif_compact_progress).progress)
            .isEqualTo(BakingProgress.PROGRESS_MAX)
    }

    /**
     * Стоящие цифры от идущих человек не отличит никак, кроме слова. В
     * свёрнутой карточке для ярлыка нет своей строки — он встаёт впереди шага,
     * чтобы обрезание хвоста уносило номер шага, а не «паузу».
     */
    @Test
    fun `a paused bake says pause in the collapsed card, ahead of the step`() {
        assertThat(compact(progress(isPaused = true)).text(R.id.notif_compact_step))
            .isEqualTo("пауза · Расстойка · шаг 3 из 8")
    }

    // ————— бумага и чернила —————

    /**
     * Панель шторки в тёмной теме телефона чёрная. Подложка и цвет текста
     * проставляются из кода поверх разметки: прошивки, перекрашивающие
     * уведомления под свою тему, ходят по уже инфлированному дереву. Если
     * setInt зовёт несуществующий метод View, здесь это и упадёт.
     */
    @Test
    fun `both cards put their own paper down and their own ink on it`() {
        listOf(compact() to R.id.notif_compact_paper, big() to R.id.notif_paper)
            .forEach { (card, paperId) ->
                assertThat(card.findViewById<View>(paperId).background).isNotNull()
            }
        assertThat(compact().findViewById<TextView>(R.id.notif_compact_timer).currentTextColor)
            .isEqualTo(ink(R.color.madre_notif_ink))
        assertThat(big().findViewById<TextView>(R.id.notif_timer_static).currentTextColor)
            .isEqualTo(ink(R.color.madre_notif_ink))
    }

    /** Бумага непрозрачна: сквозь неё не должна просвечивать чужая панель. */
    @Test
    fun `the paper is opaque, so nothing of the shade shows through it`() {
        val paper = compact().findViewById<View>(R.id.notif_compact_paper).background
        // Подложка — shape с заливкой; сама заливка проверяется по цвету бумаги.
        assertThat(paper).isNotNull()
        val flat = (paper as? ColorDrawable)?.color
        if (flat != null) assertThat(android.graphics.Color.alpha(flat)).isEqualTo(255)
    }

    /** Последние пять минут — терракота, и на обеих карточках одинаково. */
    @Test
    fun `the last five minutes turn the digits terracotta on both cards`() {
        val soon = progress(remainingSeconds = 299)
        assertThat(compact(soon).findViewById<TextView>(R.id.notif_compact_timer).currentTextColor)
            .isEqualTo(ink(R.color.madre_notif_urgent))
        assertThat(big(soon).findViewById<TextView>(R.id.notif_timer_static).currentTextColor)
            .isEqualTo(ink(R.color.madre_notif_urgent))
    }

    // ————— развёрнутая карточка —————

    @Test
    fun `the big card shows the starter, the step, the digits and the next step`() {
        val card = big()
        assertThat(card.text(R.id.notif_header)).isEqualTo("Соня · Бородинский")
        assertThat(card.text(R.id.notif_step)).isEqualTo("Расстойка · шаг 3 из 8")
        assertThat(card.text(R.id.notif_timer_static)).isEqualTo("1:02:05")
        assertThat(card.text(R.id.notif_next)).isEqualTo("дальше · Формовка")
    }

    /** Сказать нечего — ярлыка нет вовсе, а не пустое место под него. */
    @Test
    fun `the badge is gone when there is nothing to badge`() {
        assertThat(big().findViewById<View>(R.id.notif_badge).visibility).isEqualTo(View.GONE)
        assertThat(big(progress(isPaused = true)).findViewById<View>(R.id.notif_badge).visibility)
            .isEqualTo(View.VISIBLE)
    }

    /**
     * Кнопок в шторке нет: «Пауза» и «Дальше» там означали бы, что ходом
     * выпечки можно править вслепую, не глядя на страницу. Разметка проверяется
     * на это, а не только договорённость в комментарии.
     */
    @Test
    fun `neither card has a button in it`() {
        listOf(compact(), big()).forEach { card ->
            assertThat(buttonsIn(card)).isEmpty()
        }
    }

    private fun buttonsIn(view: View): List<View> = when {
        view is android.widget.Button -> listOf(view)
        view is android.view.ViewGroup ->
            (0 until view.childCount).flatMap { buttonsIn(view.getChildAt(it)) }
        else -> emptyList()
    }
}
