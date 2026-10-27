package com.polinalinen.madre.voice

import com.polinalinen.madre.model.BakingSession
import com.polinalinen.madre.model.RecipeScale
import com.polinalinen.madre.model.RecipeScaler

/** Privacy allowlist. Never serialize a ViewModel, recipe object, account or photo. */
fun BakingSession.voiceSnapshot(nowElapsed: Long, nowWall: Long, currentRevision: String): Map<String, Any?> {
    val portions = trustedPortions.takeIf { recipeRevision == currentRevision && restoredStepValid }
    val next = recipe.timeline.getOrNull(currentStepIndex + 1)
    fun scaled(text: String?): String? = if (portions == null || text == null) null
        else RecipeScaler.scaledStepText(text, portions.toDouble(), RecipeScaler.scalableBindings(recipe))
    return mapOf(
        "bake_key" to bakeKey, "recipe_id" to recipe.id, "recipe_revision" to recipeRevision,
        "recipe_name" to recipe.name, "portions" to portions, "step_index" to currentStepIndex,
        "step_count" to recipe.timeline.size, "step_title" to currentStep.title,
        "step_text" to scaled(currentStep.description), "next_step_title" to next?.title,
        "next_step_text" to scaled(next?.description), "quantities" to portions?.let { p ->
            recipe.ingredients.values.flatten().joinToString("; ") { RecipeScaler.scaledDisplayText(it, p.toDouble()) }
        },
        "paused" to isPaused, "ended" to isCompleted, "revision" to revision,
        "remaining_seconds" to remainingSeconds(nowElapsed).coerceAtLeast(0), "captured_at" to nowWall,
        "control_ready" to (portions != null && !isCompleted),
    )
}
