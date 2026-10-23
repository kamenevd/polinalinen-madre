package com.polinalinen.madre.navigation

/**
 * Экраны v4 (v4-screen-inventory.md). Navigation Compose вместо ручного
 * Screen sealed class из v3/MainActivity.kt (баг-паттерн: навигация была
 * размазана по when-веткам внутри AnimatedContent).
 */
object MadreDestinations {
    const val HOME = "home"
    const val CHAPTER = "chapter/{recipeId}?page={page}&step={step}"
    const val BAKE = "bake/{sessionId}"
    const val STARTER_DETAIL = "starter"
    const val FEEDING_FORM = "starter/feed"
    const val PHOTO_GALLERY = "photo-gallery"
    const val SETTINGS = "settings"
    const val SETTINGS_SHELF = "settings/shelf"
    const val ALICE = "settings/alice"

    // Полка (Cycle 27): корешки семьи и журнал. Формуляр выбранного человека —
    // BOOK_STATS; своя книга — owner="me" или PocketBase user id.
    const val SHELF = "shelf"
    const val BOOK_STATS = "shelf/{ownerId}"

    fun chapter(recipeId: String, page: Int = -1, step: Int = -1): String =
        "chapter/${java.net.URLEncoder.encode(recipeId, "UTF-8").replace("+", "%20")}?page=$page&step=$step"
    fun bake(sessionId: String) = "bake/$sessionId"
    fun bookStats(ownerId: String) = "shelf/$ownerId"
}
