package com.polinalinen.madre.model

import com.google.gson.annotations.SerializedName

/**
 * Full recipes database loaded from recipes.json
 * Портировано без изменений из v3 (master / feat/living-culture-v2 — идентичны).
 */
data class RecipeDatabase(
    val recipes: List<Recipe>
)

/**
 * Structured ingredient for scaling calculator.
 * Categories: flour, water, milk, starter, fat, sugar, salt, egg, leavening, filling, topping, flavor, other, ref
 * ref_type "all_of_section" → amount is dynamically computed from ref_section at runtime.
 * ref_type "portion_of_section" → amount is the original portion from ref_section.
 */
data class Ingredient(
    val name: String,
    val amount: Double,
    val unit: String,
    val category: String,
    val scalable: Boolean = true,
    @SerializedName("is_flour")
    val isFlour: Boolean = false,
    @SerializedName("fat_type")
    val fatType: String? = null,
    @SerializedName("egg_grams")
    val eggGrams: Int? = null,
    @SerializedName("ref_type")
    val refType: String? = null,
    @SerializedName("ref_section")
    val refSection: String? = null
) {
    /** Display string: "515 г пшеничной муки в/с" or "по вкусу" */
    fun displayText(scaledAmount: Double = amount): String {
        if (unit == "по вкусу" || amount == 0.0 && refType == null) return name
        val formatted = if (scaledAmount == scaledAmount.toLong().toDouble()) {
            scaledAmount.toLong().toString()
        } else {
            scaledAmount.toString()
        }
        return "$formatted $unit $name"
    }

    /** Grams equivalent for scaling math */
    fun gramsValue(scaledAmount: Double = amount): Double {
        return when {
            eggGrams != null -> scaledAmount * eggGrams
            else -> scaledAmount
        }
    }
}

/**
 * Точная ссылка на строку рецепта: имя секции и имя ингредиента ровно так, как
 * они написаны в recipes.json. По ней текст шага и находит свой вес.
 */
data class IngredientRef(val section: String, val name: String)

/**
 * Вес, названный в тексте шага, вместе с той строкой рецепта, откуда он взят.
 *
 * [amount] — не обязательно весь ингредиент: в чиабатту сначала уходит 350 мл
 * из 450 г воды, и растёт с порциями именно эта часть. [unit] тоже своя:
 * воду рецепт задаёт граммами, а шаг называет миллилитрами — для воды это одно
 * и то же число, и книга не переводит его молча, а печатает как написано.
 */
data class StepQuantity(
    val amount: Double,
    val unit: String,
    val ref: IngredientRef,
)

data class Recipe(
    val id: String,
    val name: String,
    val emoji: String,
    val description: String,
    val ingredients: Map<String, List<Ingredient>>,
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
    val durationMinutes: Int,
    @SerializedName("ingredient_stage")
    val ingredientStage: String? = null,
    // True for ACTION steps that use сливочное (butter) that should be softened
    // ahead of time — NOT olive/vegetable/sunflower oil, and not melted butter.
    @SerializedName("requires_butter_prep")
    val requiresButterPrep: Boolean = false
)

enum class StepType {
    @SerializedName("action") ACTION,
    @SerializedName("wait") WAIT
}

/**
 * Runtime state of an active baking session.
 *
 * [id] identifies one baking run among possibly several running at once
 * (bread proofing while dough for something else rests) — see
 * BakingViewModel, which now holds a list of sessions instead of one.
 */
data class BakingSession(
    val id: Long,
    val recipe: Recipe,
    val currentStepIndex: Int = 0,
    val stepStartedAtMillis: Long = System.currentTimeMillis(),
    val isPaused: Boolean = false,
    val completedAt: Long? = null,
    val accumulatedPauseMs: Long = 0,
    val scaleFactor: Double = 1.0
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
        if (isCompleted) return this
        return if (isLastStep) {
            copy(completedAt = System.currentTimeMillis(), accumulatedPauseMs = 0)
        } else {
            copy(
                currentStepIndex = currentStepIndex + 1,
                stepStartedAtMillis = System.currentTimeMillis(),
                isPaused = false,
                accumulatedPauseMs = 0
            )
        }
    }

    fun retreat(): BakingSession {
        if (currentStepIndex == 0) return this
        return copy(
            currentStepIndex = currentStepIndex - 1,
            stepStartedAtMillis = System.currentTimeMillis(),
            isPaused = false,
            accumulatedPauseMs = 0,
            completedAt = null
        )
    }

    fun togglePause(): BakingSession {
        val now = System.currentTimeMillis()
        return if (!isPaused) {
            copy(isPaused = true, accumulatedPauseMs = now)
        } else {
            val pauseDuration = if (accumulatedPauseMs > 0) now - accumulatedPauseMs else 0L
            copy(isPaused = false, stepStartedAtMillis = stepStartedAtMillis + pauseDuration, accumulatedPauseMs = 0)
        }
    }
}
