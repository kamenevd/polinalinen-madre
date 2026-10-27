package com.polinalinen.madre.shelf

enum class ShelfShareDecision {
    PUT_WITH_PHOTO,
    KEEP,
    PUT_WITHOUT_PHOTO,
}

object ShelfSharePolicy {
    val DEFAULT_DECISION: ShelfShareDecision = ShelfShareDecision.PUT_WITH_PHOTO

    const val PUT_WITH_PHOTO_LABEL = "на полке · с кадром"
    const val KEEP_LABEL = "себе"

    fun labelOf(decision: ShelfShareDecision): String = when (decision) {
        ShelfShareDecision.PUT_WITH_PHOTO -> PUT_WITH_PHOTO_LABEL
        ShelfShareDecision.PUT_WITHOUT_PHOTO -> "на полке · без кадра"
        ShelfShareDecision.KEEP -> KEEP_LABEL
    }

    fun next(decision: ShelfShareDecision): ShelfShareDecision = when (decision) {
        ShelfShareDecision.PUT_WITH_PHOTO -> ShelfShareDecision.KEEP
        ShelfShareDecision.KEEP -> ShelfShareDecision.PUT_WITH_PHOTO
        // The existing two-state Shelf review cycle is intentionally unchanged.
        ShelfShareDecision.PUT_WITHOUT_PHOTO -> ShelfShareDecision.KEEP
    }

    fun shouldEnqueue(decision: ShelfShareDecision): Boolean =
        decision == ShelfShareDecision.PUT_WITH_PHOTO || decision == ShelfShareDecision.PUT_WITHOUT_PHOTO

    fun wantsPhoto(decision: ShelfShareDecision): Boolean =
        decision == ShelfShareDecision.PUT_WITH_PHOTO
}
