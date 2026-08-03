package com.polinalinen.madre.ui.theme

import android.content.SharedPreferences
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * Cycle 11, «Спокойный режим» — ответ на жалобу на лаги прокрутки.
 *
 * Книга красива не только тем, что шевелится: статические текстуры, рамки,
 * износ и отточия остаются на месте всегда. Спокойный режим выключает ровно те
 * декорации, что либо крутят бесконечную анимацию каждый кадр, либо слушают
 * палец на всём развороте — пыль, крошки, свеча, книжный жучок, слипшиеся
 * страницы и «дыхание» страницы. По умолчанию ВКЛЮЧЕН: плавная прокрутка —
 * состояние по умолчанию, а полное оформление — осознанный выбор.
 */

/**
 * Минимальное хранилище флага. Существует затем, чтобы правило «по умолчанию
 * спокойно» и его сохранение проверялись обычным юнит-тестом, без Robolectric;
 * в приложении за ним стоят те же madre_prefs, что держат избранное и имя.
 */
interface FlagStore {
    fun getBoolean(key: String, defaultValue: Boolean): Boolean
    fun putBoolean(key: String, value: Boolean)
}

class SharedPreferencesFlagStore(private val prefs: SharedPreferences) : FlagStore {
    override fun getBoolean(key: String, defaultValue: Boolean): Boolean =
        prefs.getBoolean(key, defaultValue)

    override fun putBoolean(key: String, value: Boolean) {
        prefs.edit().putBoolean(key, value).apply()
    }
}

class CalmModeSetting(private val store: FlagStore) {

    fun isCalm(): Boolean = store.getBoolean(KEY, DEFAULT)

    fun setCalm(calm: Boolean) {
        store.putBoolean(KEY, calm)
    }

    /** Переключает и возвращает новое значение — для строки «Оформление». */
    fun toggle(): Boolean {
        val next = !isCalm()
        setCalm(next)
        return next
    }

    companion object {
        const val KEY = "calm_mode"
        const val DEFAULT = true

        fun label(calm: Boolean): String = if (calm) "спокойное" else "полное"
    }
}

/**
 * Спокойный режим читают и экраны, и модификаторы декораций, лежащие глубоко в
 * чужих поддеревьях (Modifier.breathingPage). Тащить флаг параметром через все
 * промежуточные composable — шум; CompositionLocal здесь ровно то же, чем уже
 * является AppColors. staticCompositionLocalOf: значение меняется раз в
 * полгода, зато чтение бесплатное.
 */
val LocalCalmMode = staticCompositionLocalOf { CalmModeSetting.DEFAULT }
