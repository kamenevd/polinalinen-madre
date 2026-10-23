package com.polinalinen.madre.account.yandex

import com.polinalinen.madre.notifications.BakingProgress

/** A phrase for the user's own built-in Station timer, not a remote timer command. */
object StationTimerPrompt {
    const val EXPLANATION = "Мадре напомнит на телефоне. Чтобы запустить таймер на Станции, произнесите фразу."
    const val EXPIRED = "Время шага уже вышло. Уведомление Мадре остаётся на телефоне."

    fun phrase(remainingSeconds: Int): String? {
        if (remainingSeconds <= 0) return null
        val roundedSeconds = ((remainingSeconds.toLong() + 59L) / 60L) * 60L
        val duration = BakingProgress.spokenRemaining(roundedSeconds).replace(" минута", " минуту")
        return "Алиса, поставь таймер на $duration"
    }
}
