package com.polinalinen.madre.voice

import com.polinalinen.madre.model.*
import org.junit.Assert.*
import org.junit.Test

class VoiceSnapshotTest {
    private val recipe = Recipe("bread", "Хлеб", "", "", mapOf("main" to listOf(Ingredient("муки", 100.0, "г", "flour"))),
        listOf(TimelineStep(StepType.WAIT, "Замес", "Добавить {{100 г|main:муки}}", 10)))
    private fun bake() = BakingSession(1, recipe, startedAtElapsed = 1000, startedAtWallClock = 1000,
        scaleFactor = 3.0, recipeRevision = "digest")
    @Test fun quantitiesUseExactPhoneScalerAndDataAllowlist() {
        val snapshot = bake().voiceSnapshot(1000, 1000, "digest")
        assertEquals("Добавить 300 г", snapshot["step_text"])
        assertEquals("300 г муки", snapshot["quantities"])
        assertEquals(3, snapshot["portions"])
        assertEquals(setOf("bake_key", "recipe_id", "recipe_revision", "recipe_name", "portions", "step_index", "step_count",
            "step_title", "step_text", "next_step_title", "next_step_text", "quantities", "paused", "ended", "revision",
            "remaining_seconds", "captured_at", "control_ready"), snapshot.keys)
    }
    @Test fun unknownScaleAndRecipeNeverClaimBaseQuantities() {
        for (b in listOf(bake().copy(scaleFactor = null), bake().copy(recipeRevision = null), bake().copy(restoredStepValid = false))) {
            val s = b.voiceSnapshot(1000, 1000, "digest")
            assertNull(s["portions"]); assertNull(s["step_text"]); assertNull(s["quantities"]); assertEquals(false, s["control_ready"])
        }
    }
    @Test fun remainingClampedAndPausedStable() {
        assertEquals(0L, bake().voiceSnapshot(1000000, 1000000, "digest")["remaining_seconds"])
        val paused = bake().togglePause(2000)
        assertEquals(paused.voiceSnapshot(2000, 2000, "digest")["remaining_seconds"], paused.voiceSnapshot(5000, 5000, "digest")["remaining_seconds"])
    }
}
