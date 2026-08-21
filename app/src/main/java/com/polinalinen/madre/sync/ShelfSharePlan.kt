package com.polinalinen.madre.sync

import androidx.work.ExistingWorkPolicy

/** Pure queue plan for bake stat + optional photo upload. */
data class ShelfSharePlan(
    val workName: String,
    val policy: ExistingWorkPolicy,
    val steps: List<Step>,
) {
    data class Step(val kind: String, val payload: String)

    companion object {
        fun bake(recordId: Long, bakePayload: String, photoPayload: String?): ShelfSharePlan {
            val steps = buildList {
                add(Step(kind = SyncWorker.KIND_BAKE, payload = bakePayload))
                val photo = photoPayload?.trim().orEmpty()
                if (photo.isNotEmpty()) {
                    add(Step(kind = SyncWorker.KIND_BAKE_PHOTO, payload = photo))
                }
            }
            return ShelfSharePlan(
                workName = "sync-bake-chain-$recordId",
                policy = ExistingWorkPolicy.KEEP,
                steps = steps,
            )
        }
    }
}
