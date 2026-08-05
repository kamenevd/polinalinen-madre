package com.polinalinen.madre.notifications

import com.polinalinen.madre.data.db.entities.ActiveBakeEntity

/**
 * Cycle 15: что делать с каждой незавершённой выпечкой после перезагрузки
 * телефона.
 *
 * Чистая функция над строкой active_bakes — ни Room, ни WorkManager, ни
 * Android: решение здесь, а разговор с системой — в [BakeRestoreWorker].
 * Считает по стенным часам, и только по ним: монотонные (elapsedRealtime) ребут
 * обнулил, и от прежней сессии в них не осталось ничего (см.
 * BakingSession.rebasedTo).
 */
object BootRestorePlanner {

    /**
     * Сколько времени после конца шага выпечку ещё имеет смысл воскрешать.
     * Полсуток — граница между «телефон перезагрузился ночью, тесто ждёт с
     * утра» и «эту выпечку бросили неделю назад»: звать человека к тесту,
     * которого давно нет, книга не должна.
     */
    const val STALE_AFTER_HOURS = 12

    sealed interface Decision {
        /** Шаг закончится через [delayMillis] (0 — уже закончился, пока телефон был выключен). */
        data class Notify(val sessionId: Long, val delayMillis: Long) : Decision

        /** Строку храним, но не звоним: пауза или шаг-действие, у которого нет своего срока. */
        data class Keep(val sessionId: Long) : Decision

        /** Выпечки давно нет — строку стереть. */
        data class Forget(val sessionId: Long) : Decision
    }

    private const val STALE_AFTER_MILLIS = STALE_AFTER_HOURS * 3_600_000L

    fun decide(bake: ActiveBakeEntity, nowWallClock: Long): Decision {
        val pausedAt = bake.pausedAtWallClock
        if (pausedAt != null) {
            // На паузе шаг заморожен: срок не идёт, и звонить не о чем. Но
            // пауза, которой больше полусуток, — это уже не пауза, а забытая
            // выпечка.
            return if (nowWallClock - pausedAt > STALE_AFTER_MILLIS) Decision.Forget(bake.sessionId)
            else Decision.Keep(bake.sessionId)
        }
        val endsAt = bake.startedAtWallClock + bake.stepDurationMinutes * 60_000L
        if (nowWallClock - endsAt > STALE_AFTER_MILLIS) return Decision.Forget(bake.sessionId)
        // У шага-действия «время» — оценка автора рецепта, а не таймер: то же
        // правило, по которому молчит BakingNotificationPlanner.isStepDone.
        if (!bake.isWaitStep) return Decision.Keep(bake.sessionId)
        return Decision.Notify(bake.sessionId, (endsAt - nowWallClock).coerceAtLeast(0L))
    }

    fun plan(bakes: List<ActiveBakeEntity>, nowWallClock: Long): List<Pair<ActiveBakeEntity, Decision>> =
        bakes.map { it to decide(it, nowWallClock) }

    /** Одно имя работы на выпечку: перепланирование заменяет прежний срок, а не добавляет второй. */
    fun uniqueWorkName(sessionId: Long): String = "bake-step-end-$sessionId"
}
