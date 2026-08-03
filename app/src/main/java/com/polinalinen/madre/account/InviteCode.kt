package com.polinalinen.madre.account

/**
 * Код приглашения в семейную книгу (DESIGN-V4.md Cycle 11, фича 28).
 *
 * Код диктуют вслух и переписывают от руки, поэтому алфавит — Crockford
 * base32: из него выброшены I, L, O и U (первые три путаются с 1 и 0, U — с V
 * в рукописи), а при разборе O читается как 0, а I и L как 1. Регистр и
 * разделители не значат ничего.
 *
 * Прощаем мы только начертание — не длину и не чужие знаки: 16 знаков
 * тридцатидвухбуквенного алфавита дают ровно 80 бит, и укорачивать код ради
 * удобства нельзя. Сами коды приложение не выпускает — их печатает сервер
 * ($security.randomStringWithAlphabet), здесь только разбор и вид на бумаге.
 */
object InviteCode {

    const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    const val LENGTH = 16
    /** log2(32) = 5 бит на знак. */
    const val ENTROPY_BITS = LENGTH * 5

    private const val GROUP = 4
    private const val SEPARATORS = "-_.,/\\|"

    /**
     * Приводит написанное от руки к каноническому виду или возвращает null,
     * если это вообще не код.
     */
    fun normalize(raw: String): String? {
        val folded = StringBuilder(LENGTH)
        for (symbol in raw.uppercase()) {
            val canonical = when (symbol) {
                'O' -> '0'
                'I', 'L' -> '1'
                else -> symbol
            }
            when {
                canonical in ALPHABET -> folded.append(canonical)
                canonical.isWhitespace() || canonical in SEPARATORS -> Unit
                else -> return null
            }
        }
        return folded.toString().takeIf { it.length == LENGTH }
    }

    fun isValid(raw: String): Boolean = normalize(raw) != null

    /** Код на бумаге — четвёрками через дефис; чужое отдаётся как есть. */
    fun format(code: String): String =
        if (code.length != LENGTH) code else code.chunked(GROUP).joinToString("-")
}
