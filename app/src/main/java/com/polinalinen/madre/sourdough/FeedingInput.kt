package com.polinalinen.madre.sourdough

/**
 * Cycle 12: что считается настоящим кормлением.
 *
 * Форма не проверяла ничего: пустые поля читались как `toIntOrNull() ?: 0`, и
 * «Вписать в дневник» заводило запись «0 г муки, 0 г воды». Запись уходила в
 * Room, сдвигала дату последнего кормления, меняла фазу закваски и
 * переставляла напоминание — а убрать её из книги нечем.
 *
 * Правило живёт отдельно от экрана, потому что оно про закваску, а не про
 * вёрстку: кормление — это мука И вода, обе больше нуля и обе в пределах
 * разумного для банки на кухне.
 */
object FeedingInput {

    /** Два килограмма муки — уже не кормление, а мешок; почти всегда лишний ноль. */
    const val MAX_GRAMS = 2000

    data class Validation(
        val flourGrams: Int?,
        val waterGrams: Int?,
        val message: String?,
    ) {
        val canSave: Boolean get() = flourGrams != null && waterGrams != null
    }

    fun validate(flourText: String, waterText: String): Validation {
        val flour = grams(flourText)
        val water = grams(waterText)
        val message = when {
            flour != null && water != null -> null
            flourText.isBlank() || waterText.isBlank() ->
                "впишите муку и воду — сколько дали закваске"
            tooMuch(flourText) || tooMuch(waterText) ->
                "столько за раз не кормят: не больше $MAX_GRAMS г"
            else -> "мука и вода — в граммах, больше нуля"
        }
        return Validation(flour, water, message)
    }

    private fun grams(text: String): Int? =
        text.trim().toIntOrNull()?.takeIf { it in 1..MAX_GRAMS }

    private fun tooMuch(text: String): Boolean =
        (text.trim().toIntOrNull() ?: 0) > MAX_GRAMS
}
