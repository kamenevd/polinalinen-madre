package com.polinalinen.madre.sync

/** Seam for enqueueing shelf sync from BakingViewModel. */
interface ShelfSync {
    fun shareBakeStat(
        recordId: Long,
        recipeId: String,
        recipeName: String,
        portions: Int,
        bakedAtMillis: Long,
        displayName: String? = null,
        familyName: String? = null,
        photoPath: String? = null,
    )
}
