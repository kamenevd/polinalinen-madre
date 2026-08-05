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
 * Часы выпечки — единственное место, где модель времени касается системы.
 *
 * Двое часов, и каждые для своего (Cycle 15):
 * [elapsed] монотонны и не двигаются ни от смены пояса, ни от перевода на
 * летнее время — по ним и только по ним считается остаток шага;
 * [wallClock] переживают перезагрузку — по ним запись ложится в формуляр и по
 * ним же сессия восстанавливается после ребута (BakingSession.rebasedTo).
 */
object BakingClock {
    fun elapsed(): Long = android.os.SystemClock.elapsedRealtime()
    fun wallClock(): Long = System.currentTimeMillis()
}

/**
 * Runtime state of an active baking session.
 *
 * [id] identifies one baking run among possibly several running at once
 * (bread proofing while dough for something else rests) — see
 * BakingViewModel, which now holds a list of sessions instead of one.
 *
 * Часов сама не читает: «сейчас» приходит параметром — так отсчёт проверяется
 * тестом без Android и не зависит от того, что творится с системным временем
 * между двумя тиками таймера.
 */
data class BakingSession(
    val id: Long,
    val recipe: Recipe,
    val currentStepIndex: Int = 0,
    /** Начало текущего шага по монотонным часам — основа отсчёта остатка. */
    val startedAtElapsed: Long,
    /** Тот же момент по стенным часам — для истории и восстановления после ребута. */
    val startedAtWallClock: Long,
    val isPaused: Boolean = false,
    val completedAt: Long? = null,
    /**
     * Момент паузы по монотонным часам; null — сейчас не на паузе. Именно
     * null, а не ноль: после ребута [rebasedTo] уводит отметки в отрицательные
     * числа (шаг начался раньше загрузки), и ноль как признак «паузы нет» там
     * тихо разморозил бы отсчёт.
     */
    val pausedAtElapsed: Long? = null,
    val scaleFactor: Double = 1.0
) {
    companion object {
        /** Завести сессию «сейчас» — единственный путь, которым в неё попадают часы. */
        fun start(id: Long, recipe: Recipe, scaleFactor: Double = 1.0): BakingSession =
            BakingSession(
                id = id,
                recipe = recipe,
                startedAtElapsed = BakingClock.elapsed(),
                startedAtWallClock = BakingClock.wallClock(),
                scaleFactor = scaleFactor,
            )
    }

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

    /**
     * Сколько шаг уже идёт. На паузе счёт стоит на моменте паузы — простоявшее
     * время шага не съедает.
     */
    fun elapsedSeconds(nowElapsed: Long): Long {
        val until = if (isPaused) pausedAtElapsed ?: nowElapsed else nowElapsed
        return ((until - startedAtElapsed) / 1000).coerceAtLeast(0)
    }

    /** Остаток текущего шага. Отсчёт один на всё приложение — экран и шторка берут его отсюда. */
    fun remainingSeconds(nowElapsed: Long): Long =
        (currentStep.durationMinutes * 60L - elapsedSeconds(nowElapsed)).coerceAtLeast(0)

    /**
     * Перезагрузка обнулила монотонные часы, и старый [startedAtElapsed] стал
     * числом из прошлой жизни телефона. Пересчитываем его по стенным часам —
     * единственному, что ребут пережило: шаг начался [startedAtWallClock], то
     * есть столько-то миллисекунд назад, столько же назад он начался и по
     * новым монотонным часам. Смещение паузы сохраняем как есть — на паузе
     * остаток и так заморожен.
     */
    fun rebasedTo(nowElapsed: Long, nowWallClock: Long): BakingSession {
        val sinceStart = (nowWallClock - startedAtWallClock).coerceAtLeast(0)
        val rebasedStart = nowElapsed - sinceStart
        return copy(
            startedAtElapsed = rebasedStart,
            pausedAtElapsed = pausedAtElapsed?.let { rebasedStart + (it - startedAtElapsed) },
        )
    }

    fun advance(nowElapsed: Long, nowWallClock: Long): BakingSession {
        if (isCompleted) return this
        return if (isLastStep) {
            copy(completedAt = nowWallClock, pausedAtElapsed = null)
        } else {
            copy(
                currentStepIndex = currentStepIndex + 1,
                startedAtElapsed = nowElapsed,
                startedAtWallClock = nowWallClock,
                isPaused = false,
                pausedAtElapsed = null
            )
        }
    }

    fun retreat(nowElapsed: Long, nowWallClock: Long): BakingSession {
        if (currentStepIndex == 0) return this
        return copy(
            currentStepIndex = currentStepIndex - 1,
            startedAtElapsed = nowElapsed,
            startedAtWallClock = nowWallClock,
            isPaused = false,
            pausedAtElapsed = null,
            completedAt = null
        )
    }

    /**
     * Продолжение сдвигает начало шага на всю длину паузы — и по монотонным
     * часам, и по стенным: оба поля означают один момент, «когда пошёл отсчёт
     * этого шага», и разъехаться им нельзя, иначе [rebasedTo] после ребута
     * вернёт паузу обратно в счёт.
     */
    fun togglePause(nowElapsed: Long): BakingSession {
        return if (!isPaused) {
            copy(isPaused = true, pausedAtElapsed = nowElapsed)
        } else {
            val pauseDuration = pausedAtElapsed?.let { (nowElapsed - it).coerceAtLeast(0) } ?: 0L
            copy(
                isPaused = false,
                startedAtElapsed = startedAtElapsed + pauseDuration,
                startedAtWallClock = startedAtWallClock + pauseDuration,
                pausedAtElapsed = null
            )
        }
    }
}
