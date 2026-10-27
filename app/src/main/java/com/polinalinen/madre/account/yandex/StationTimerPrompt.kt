package com.polinalinen.madre.account.yandex


/** A phrase for the user's own built-in Station timer, not a remote timer command. */
object StationTimerPrompt {
    const val EXPLANATION = "Мадре напомнит на телефоне. Чтобы запустить таймер на Станции, произнесите фразу."
    const val EXPIRED = "Время шага уже вышло. Уведомление Мадре остаётся на телефоне."

    fun phrase(remainingSeconds: Long): String? {
        if (remainingSeconds <= 0) return null
        val minutes = remainingSeconds / 60L + if (remainingSeconds % 60L > 0L) 1L else 0L
        val unit = when {
            minutes % 10L == 1L && minutes % 100L != 11L -> "минуту"
            minutes % 10L in 2L..4L && minutes % 100L !in 12L..14L -> "минуты"
            else -> "минут"
        }
        return "Алиса, поставь таймер на $minutes $unit"
    }
}
