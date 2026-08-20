package com.polinalinen.madre.navigation

/**
 * Экраны v4 (v4-screen-inventory.md). Navigation Compose вместо ручного
 * Screen sealed class из v3/MainActivity.kt (баг-паттерн: навигация была
 * размазана по when-веткам внутри AnimatedContent).
 */
object MadreDestinations {
    const val HOME = "home"
    const val RECIPE_DETAIL = "recipe/{recipeId}"
    const val BAKING_TIMER = "baking/{sessionId}"
    const val BAKING_COMPLETE = "baking/{sessionId}/complete"
    const val STARTER_DETAIL = "starter"
    const val FEEDING_FORM = "starter/feed"
    const val PHOTO_GALLERY = "photo-gallery"
    const val SETTINGS = "settings"
    const val SETTINGS_SHELF = "settings/shelf"

    // Полка (Cycle 27): корешки семьи и журнал. Формуляр выбранного человека —
    // BOOK_STATS; своя книга — owner="me" или PocketBase user id.
    const val SHELF = "shelf"
    const val BOOK_STATS = "shelf/{ownerId}"

    fun recipeDetail(recipeId: String) = "recipe/$recipeId"
    fun bakingTimer(sessionId: String) = "baking/$sessionId"
    fun bakingComplete(sessionId: String) = "baking/$sessionId/complete"
    fun bookStats(ownerId: String) = "shelf/$ownerId"
}
