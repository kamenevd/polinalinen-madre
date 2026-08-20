package com.polinalinen.madre.sync

import androidx.work.ExistingWorkPolicy
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShelfSharePlanTest {

    @Test
    fun `plan with photo has two ordered steps and keep policy`() {
        val plan = ShelfSharePlan.bake(
            recordId = 42L,
            bakePayload = """{"kind":"bake"}""",
            photoPayload = """{"kind":"photo"}""",
        )

        assertThat(plan.workName).isEqualTo("sync-bake-chain-42")
        assertThat(plan.policy).isEqualTo(ExistingWorkPolicy.KEEP)
        assertThat(plan.steps.map { it.kind }).containsExactly(
            SyncWorker.KIND_BAKE,
            SyncWorker.KIND_BAKE_PHOTO,
        ).inOrder()
    }

    @Test
    fun `plan without photo has one bake step`() {
        val plan = ShelfSharePlan.bake(
            recordId = 7L,
            bakePayload = "{}",
            photoPayload = null,
        )

        assertThat(plan.workName).isEqualTo("sync-bake-chain-7")
        assertThat(plan.steps).hasSize(1)
        assertThat(plan.steps.single().kind).isEqualTo(SyncWorker.KIND_BAKE)
    }
}
