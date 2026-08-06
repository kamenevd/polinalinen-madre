package com.polinalinen.madre.data.db.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Cycle 15: снимок текущего шага НЕзавершённой выпечки — единственное, что от
 * неё остаётся, если телефон перезагрузили.
 *
 * До сих пор сессии жили только в памяти BakingViewModel (bake_records
 * заводится один раз, уже готовой выпечке). Ребут уносил вместе с процессом и
 * отсчёт, и уведомление о конце шага: человек узнавал о подошедшей расстойке,
 * только когда сам открывал книгу. Отсюда таблица — и BootReceiver, который её
 * читает.
 *
 * Хранится не вся сессия, а ровно то, чем шаг заканчивается: рецепт, название
 * шага и его длительность записаны прямо здесь, чтобы восстановление не
 * поднимало recipes.json и не зависело от того, не переехал ли рецепт между
 * версиями книги. Строка живёт столько же, сколько идёт выпечка: заводится на
 * старте шага, переписывается на каждом переходе и удаляется, когда выпечку
 * закончили или бросили.
 */
@Entity(tableName = "active_bakes")
data class ActiveBakeEntity(
    /** id сессии из BakingViewModel — одна строка на одну выпечку. */
    @PrimaryKey val sessionId: Long,
    val recipeId: String,
    val recipeName: String,
    val stepTitle: String,
    val stepIndex: Int,
    val stepDurationMinutes: Int,
    /**
     * Шаг-ожидание. У шагов-действий «длительность» — оценка, а не таймер, и
     * звать человека по её истечении незачем (то же правило, что в
     * BakingNotificationPlanner.isStepDone).
     */
    val isWaitStep: Boolean,
    /** Начало шага по стенным часам — единственные часы, пережившие ребут. */
    val startedAtWallClock: Long,
    /** Момент паузы по стенным часам; null — выпечка идёт. */
    val pausedAtWallClock: Long? = null,
)
