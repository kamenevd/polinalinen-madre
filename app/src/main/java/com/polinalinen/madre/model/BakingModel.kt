package com.polinalinen.madre.model

import com.google.gson.annotations.SerializedName

/**
 * Full recipes database loaded from recipes.json
 */
data class RecipeDatabase(
    val recipes: List<Recipe>
)

data class Recipe(
    val id: String,
    val name: String,
    val emoji: String,
    val description: String,
    val ingredients: Map<String, List<String>>,
    val timeline: List<TimelineStep>,
    val difficulty: Int = 0,  // 0 = not set, 1–5 difficulty dots
    val tasteReviews: List<TasteReview> = emptyList(),
    val servingTips: List<ServingTip> = emptyList(),
    val variations: List<ServingTip> = emptyList()
)

data class TasteReview(
    val author: String,
    val avatarEmoji: String,
    val rating: Int,
    val text: String
)

data class ServingTip(
    val emoji: String,
    val text: String
)

data class TimelineStep(
    val type: StepType,
    val title: String,
    val description: String,
    @SerializedName("duration_minutes")
    val durationMinutes: Int
)

enum class StepType {
    @SerializedName("action") ACTION,
    @SerializedName("wait") WAIT
}

/**
 * Runtime state of an active baking session
 */
data class BakingSession(
    val recipe: Recipe,
    val currentStepIndex: Int = 0,
    val stepStartedAtMillis: Long = System.currentTimeMillis(),
    val isPaused: Boolean = false,
    val completedAt: Long? = null
) {
    val currentStep: TimelineStep
        get() = recipe.timeline.getOrElse(currentStepIndex) {
            recipe.timeline.lastOrNull() ?: TimelineStep(StepType.WAIT, "—", "", 0)
        }

    val isLastStep: Boolean
        get() = currentStepIndex >= recipe.timeline.size - 1

    val isCompleted: Boolean
        get() = completedAt != null

    val progress: Float
        get() = if (recipe.timeline.isEmpty()) 0f
        else (currentStepIndex + 1).toFloat() / recipe.timeline.size

    val totalDurationMinutes: Int
        get() = recipe.timeline.sumOf { it.durationMinutes }

    fun advance(): BakingSession {
        if (isCompleted) return this // already completed, no-op
        return if (isLastStep) {
            copy(completedAt = System.currentTimeMillis())
        } else {
            copy(
                currentStepIndex = currentStepIndex + 1,
                stepStartedAtMillis = System.currentTimeMillis(),
                isPaused = false
            )
        }
    }

    fun togglePause(): BakingSession {
        return copy(isPaused = !isPaused)
    }
}
