package com.polinalinen.madre.shelf

import android.content.SharedPreferences

/**
 * Как выпечка оказывается на полке.
 *
 * Всегда — факт уходит в очередь в момент готовности, на экране «Испечено»
 * только штамп «на полке», без второго экрана. Спросить — один лист на
 * готовности: поставить, поставить с кадром или оставить себе.
 */
enum class ShelfShareMode {
    ALWAYS,
    ASK,
}

enum class ShelfShareDecision {
    PUT,
    PUT_WITH_PHOTO,
    KEEP,
}

object ShelfSharePolicy {

    const val PREFS = "madre_prefs"
    const val KEY = "shelf_share_mode"

    const val SETTING_LABEL = "Ставить выпечку на полку"
    const val ALWAYS_LABEL = "всегда"
    const val ASK_LABEL = "спросить при готовности"

    const val SHEET_TITLE = "Поставить на полку?"
    const val PUT_LABEL = "Поставить"
    const val PUT_WITH_PHOTO_LABEL = "Поставить с кадром"
    const val KEEP_LABEL = "Оставить себе"
    const val ON_SHELF_STAMP = "на полке"

    fun read(prefs: SharedPreferences): ShelfShareMode = parse(prefs.getString(KEY, null))

    fun write(prefs: SharedPreferences, mode: ShelfShareMode) {
        prefs.edit().putString(KEY, mode.name).apply()
    }

    fun parse(raw: String?): ShelfShareMode =
        when (raw) {
            ShelfShareMode.ASK.name -> ShelfShareMode.ASK
            else -> ShelfShareMode.ALWAYS
        }

    fun labelOf(mode: ShelfShareMode): String = when (mode) {
        ShelfShareMode.ALWAYS -> ALWAYS_LABEL
        ShelfShareMode.ASK -> ASK_LABEL
    }

    /** Очередь в момент готовности — только при «всегда» и только если есть куда слать. */
    fun shouldShareOnComplete(mode: ShelfShareMode, sharingAvailable: Boolean): Boolean =
        sharingAvailable && mode == ShelfShareMode.ALWAYS

    /** Лист на готовности — только при «спросить». */
    fun shouldAskOnComplete(mode: ShelfShareMode, sharingAvailable: Boolean): Boolean =
        sharingAvailable && mode == ShelfShareMode.ASK

    /** Штамп без листа — когда факт уже ушёл сам. */
    fun showOnShelfStamp(mode: ShelfShareMode, sharingAvailable: Boolean): Boolean =
        shouldShareOnComplete(mode, sharingAvailable)

    fun shouldEnqueue(decision: ShelfShareDecision): Boolean = when (decision) {
        ShelfShareDecision.PUT, ShelfShareDecision.PUT_WITH_PHOTO -> true
        ShelfShareDecision.KEEP -> false
    }

    fun wantsPhoto(decision: ShelfShareDecision): Boolean =
        decision == ShelfShareDecision.PUT_WITH_PHOTO
}
