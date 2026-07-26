package com.polinalinen.madre.ui.photo

/**
 * «Оформление фотокарточки» (Cycle 11): что именно человек выбрал в редакторе
 * перед тем, как вклеить снимок в книгу. Чистая модель без Android/Compose —
 * вся арифметика выбора лежит здесь и проверяется юнит-тестами, а рисование
 * живёт отдельно в [PhotoDecorRenderer].
 *
 * Ничего сетевого и никакой генерации: рамки, тёплый фильтр и штампы рисуются
 * локально на Canvas по описанию из этой модели.
 */

/** Бумажная рамка/паспарту вокруг снимка. */
enum class PhotoFrame(val label: String) {
    NONE("без рамки"),
    MAT("паспарту"),
    DECKLE("рваный край"),
    HOLDERS("уголки"),
}

/** Штамп-наклейка. Только рисунок, без единой буквы — как оттиск на бумаге. */
enum class PhotoStamp(val label: String) {
    WHEAT("колос"),
    LOAF("каравай"),
    ROSETTE("розетка"),
}

/** Куда лечь штампу. Ровно четыре угла — середины страницы не бывает. */
enum class StampCorner(val label: String) {
    TOP_LEFT("сверху слева"),
    TOP_RIGHT("сверху справа"),
    BOTTOM_LEFT("снизу слева"),
    BOTTOM_RIGHT("снизу справа"),
}

/**
 * Выбор оформления. Штамп — не больше одного: [withStamp] переключает, а
 * повторный тап по уже выбранному снимает его совсем (иначе выбранный штамп
 * нельзя было бы убрать, не выходя из редактора).
 */
data class PhotoDecor(
    val frame: PhotoFrame = PhotoFrame.MAT,
    val warm: Boolean = true,
    val stamp: PhotoStamp? = null,
    val stampCorner: StampCorner = StampCorner.BOTTOM_RIGHT,
) {
    /** Ничего не выбрано — итог визуально неотличим от исходника. */
    val isPlain: Boolean get() = frame == PhotoFrame.NONE && !warm && stamp == null

    fun withFrame(next: PhotoFrame): PhotoDecor = copy(frame = next)

    fun toggleWarm(): PhotoDecor = copy(warm = !warm)

    fun withStamp(next: PhotoStamp): PhotoDecor = copy(stamp = if (stamp == next) null else next)

    fun withCorner(corner: StampCorner): PhotoDecor = copy(stampCorner = corner)

    companion object {
        /** Оформление «как было» — то, что сохраняет кнопка «без оформления». */
        val PLAIN = PhotoDecor(frame = PhotoFrame.NONE, warm = false, stamp = null)
    }
}
