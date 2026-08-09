package com.polinalinen.madre.sourdough

/**
 * Cycle 18: зов покормить закваску — то, что стоит на первой полосе главным
 * действием дня.
 *
 * Кормление — единственное, что делают в этой книге каждый день, а дорога к
 * нему шла через три экрана: первая полоса → дневник → форма. Здесь считается
 * только текст и громкость кнопки; открывает форму сама страница.
 *
 * Ничего от Android и ничего от Compose: «пора ли кормить» — вопрос о
 * закваске, и проверяется он на числах, а не на раскладке.
 */
object FeedingCall {

    /**
     * Кормили недавно — кнопка остаётся нажимаемой, но перестаёт быть главным
     * действием страницы.
     *
     * Пока закваска поднимается и стоит на пике, кормить её рано: свежее
     * кормление сбрасывает поднявшуюся культуру. Пустой дневник сюда не
     * попадает: первое кормление — тоже кормление, и звать на него надо в
     * полный голос.
     */
    fun isFresh(phase: GrowthPhase): Boolean = when (phase) {
        GrowthPhase.LAG, GrowthPhase.GROWING, GrowthPhase.PEAK -> true
        GrowthPhase.DECLINING, GrowthPhase.HUNGRY, GrowthPhase.EMPTY -> false
    }

    /**
     * Надпись на кнопке.
     *
     * «Покормить» просит винительного падежа, а склонять чужое имя книге
     * нечем — та же причина, по которой [StarterName.feedingPhotoCaption]
     * ставит точку вместо падежа, а [StarterName.hungryTitle] обходится без
     * «проголодалась». Имя книги несклоняемое, и с ним закваску зовут по
     * имени; всякое другое книга не коверкает и говорит «закваску».
     *
     * Потерять здесь имя жалко, но «Покормить Соня» — не по-русски, а
     * «Покормить Бориса» — угадано. Имя всё равно стоит строкой выше, в
     * [StarterName.homeLabel].
     */
    fun label(name: String): String {
        val sanitized = StarterName.sanitize(name)
        return if (sanitized.equals(StarterName.DEFAULT, ignoreCase = true)) {
            "Покормить ${StarterName.DEFAULT}"
        } else {
            "Покормить закваску"
        }
    }

    /**
     * Когда кормили в прошлый раз — подпись под кнопкой. null, если кормления
     * ещё не было: выдумывать «кормили 0м назад» для пустого дневника незачем.
     */
    fun sinceLabel(hoursSinceFeeding: Float?): String? {
        val hours = hoursSinceFeeding ?: return null
        return "кормили ${formatHourOffset(hours)} назад"
    }
}
