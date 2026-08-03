package com.polinalinen.madre.notifications

import com.polinalinen.madre.model.BakingSession
import com.polinalinen.madre.model.StepType

/**
 * Cycle 11: два уведомления по ходу выпечки — «шаг ожидания закончился» и
 * «достаньте сливочное масло» за полчаса до шага, которому нужно мягкое масло
 * (TimelineStep.requiresButterPrep из recipes.json).
 *
 * Условия — чистые функции над сессией, чтобы проверялись юнит-тестом; за то,
 * чтобы каждое сработало ровно один раз, отвечает [NotificationLedger].
 */
object BakingNotificationPlanner {

    /** За сколько до шага с маслом просить достать его из холодильника. */
    const val BUTTER_PREP_LEAD_MINUTES = 30

    fun stepDoneKey(sessionId: Long, stepIndex: Int): String = "step-done-$sessionId-$stepIndex"

    fun butterPrepKey(sessionId: Long, stepIndex: Int): String = "butter-prep-$sessionId-$stepIndex"

    /**
     * Шаг ожидания досчитал до нуля. Шаги-действия сюда не попадают: их «время»
     * — это оценка, а не таймер, и звать человека по её истечении незачем.
     */
    fun isStepDone(session: BakingSession, remainingSeconds: Long): Boolean {
        if (session.isPaused || session.isCompleted) return false
        if (session.currentStep.type != StepType.WAIT) return false
        return remainingSeconds <= 0L
    }

    /** До шага, которому нужно мягкое масло, осталось не больше получаса. */
    fun isButterPrepDue(session: BakingSession, remainingSeconds: Long): Boolean {
        if (session.isPaused || session.isCompleted) return false
        val nextStep = session.recipe.timeline.getOrNull(session.currentStepIndex + 1) ?: return false
        if (!nextStep.requiresButterPrep) return false
        return remainingSeconds <= BUTTER_PREP_LEAD_MINUTES * 60L
    }
}

/**
 * Журнал уже показанного: ключ проходит один раз, дальше молчание. Заводится
 * экземпляром на владельца (BakingViewModel), а не статическим объектом —
 * глобальное mutable-состояние переживало бы сам процесс выпечки и делало бы
 * тесты зависимыми друг от друга.
 *
 * Живёт в памяти: после убийства процесса журнал пуст, как и сами сессии
 * выпечки — они тоже не переживают перезапуск.
 */
class NotificationLedger {

    private val sent = mutableSetOf<String>()

    /** true — если это уведомление ещё не показывали (и теперь помечено). */
    fun markIfNew(key: String): Boolean = sent.add(key)

    /**
     * Сессия закрыта — её ключи больше не нужны, чужие не трогаем. Совпадение
     * строгое, по форме ключа «<вид>-<сессия>-<шаг>»: подстрокой сессия 1
     * зацепила бы и сессию 21, и шаг №1 чужой выпечки.
     */
    fun forgetSession(sessionId: Long) {
        val pattern = Regex("""^[a-z-]+-$sessionId-\d+$""")
        sent.removeAll { pattern.matches(it) }
    }
}
