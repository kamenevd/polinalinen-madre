package com.polinalinen.madre.shelf

enum class ShelfShareDecision {
    PUT_WITH_PHOTO,
    KEEP,
}

object ShelfSharePolicy {
    val DEFAULT_DECISION: ShelfShareDecision = ShelfShareDecision.PUT_WITH_PHOTO

    const val PUT_WITH_PHOTO_LABEL = "на полке · с кадром"
    const val KEEP_LABEL = "себе"

    fun labelOf(decision: ShelfShareDecision): String = when (decision) {
        ShelfShareDecision.PUT_WITH_PHOTO -> PUT_WITH_PHOTO_LABEL
        ShelfShareDecision.KEEP -> KEEP_LABEL
    }

    fun next(decision: ShelfShareDecision): ShelfShareDecision = when (decision) {
        ShelfShareDecision.PUT_WITH_PHOTO -> ShelfShareDecision.KEEP
        ShelfShareDecision.KEEP -> ShelfShareDecision.PUT_WITH_PHOTO
    }

    fun shouldEnqueue(decision: ShelfShareDecision): Boolean =
        decision == ShelfShareDecision.PUT_WITH_PHOTO

    fun wantsPhoto(decision: ShelfShareDecision): Boolean =
        decision == ShelfShareDecision.PUT_WITH_PHOTO
}
