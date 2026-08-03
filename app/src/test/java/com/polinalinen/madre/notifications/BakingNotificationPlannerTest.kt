package com.polinalinen.madre.notifications

import com.google.common.truth.Truth.assertThat
import com.polinalinen.madre.model.BakingSession
import com.polinalinen.madre.model.Recipe
import com.polinalinen.madre.model.StepType
import com.polinalinen.madre.model.TimelineStep
import org.junit.Test

/**
 * Cycle 11, уведомления выпечки: о конце шага-ожидания и о сливочном масле,
 * которое надо достать заранее. Оба правила — чистые функции над сессией, плюс
 * журнал уже отправленного: одно уведомление каждого вида на пару
 * «сессия + шаг», сколько бы раз таймер ни тикнул.
 */
class BakingNotificationPlannerTest {

    private fun recipe(vararg steps: TimelineStep) = Recipe(
        id = "bread",
        name = "Хлеб",
        emoji = "",
        description = "",
        ingredients = emptyMap(),
        timeline = steps.toList(),
    )

    private fun wait(minutes: Int) = TimelineStep(StepType.WAIT, "Расстойка", "", minutes)
    private fun action(minutes: Int, butter: Boolean = false) =
        TimelineStep(StepType.ACTION, "Замес", "", minutes, requiresButterPrep = butter)

    private fun session(recipe: Recipe, stepIndex: Int = 0) =
        BakingSession(id = 1L, recipe = recipe, currentStepIndex = stepIndex)

    @Test
    fun `a wait step that has run out is worth a notification`() {
        val s = session(recipe(wait(60), action(10)))
        assertThat(BakingNotificationPlanner.isStepDone(s, remainingSeconds = 0L)).isTrue()
    }

    @Test
    fun `a wait step still running is not`() {
        val s = session(recipe(wait(60), action(10)))
        assertThat(BakingNotificationPlanner.isStepDone(s, remainingSeconds = 1L)).isFalse()
    }

    @Test
    fun `a paused or finished bake never fires`() {
        val recipe = recipe(wait(60), action(10))
        val paused = session(recipe).copy(isPaused = true)
        assertThat(BakingNotificationPlanner.isStepDone(paused, remainingSeconds = 0L)).isFalse()

        val done = session(recipe).copy(completedAt = 1L)
        assertThat(BakingNotificationPlanner.isStepDone(done, remainingSeconds = 0L)).isFalse()
    }

    @Test
    fun `an action step ending does not pretend to be a wait timer`() {
        val s = session(recipe(action(10), wait(60)))
        assertThat(BakingNotificationPlanner.isStepDone(s, remainingSeconds = 0L)).isFalse()
    }

    @Test
    fun `butter is asked for exactly thirty minutes before the step that needs it`() {
        val s = session(recipe(wait(120), action(15, butter = true)))
        val thirtyMinutes = 30 * 60L

        assertThat(BakingNotificationPlanner.isButterPrepDue(s, thirtyMinutes + 60)).isFalse()
        assertThat(BakingNotificationPlanner.isButterPrepDue(s, thirtyMinutes)).isTrue()
        assertThat(BakingNotificationPlanner.isButterPrepDue(s, 5 * 60L)).isTrue()
        assertThat(BakingNotificationPlanner.BUTTER_PREP_LEAD_MINUTES).isEqualTo(30)
    }

    @Test
    fun `no butter reminder when the next step does not need butter`() {
        val s = session(recipe(wait(120), action(15, butter = false)))
        assertThat(BakingNotificationPlanner.isButterPrepDue(s, 10 * 60L)).isFalse()
    }

    @Test
    fun `no butter reminder on the last step - there is no next one`() {
        val s = session(recipe(wait(120)), stepIndex = 0)
        assertThat(BakingNotificationPlanner.isButterPrepDue(s, 10 * 60L)).isFalse()
    }

    @Test
    fun `keys are scoped per session and step`() {
        assertThat(BakingNotificationPlanner.stepDoneKey(1L, 2)).isEqualTo("step-done-1-2")
        assertThat(BakingNotificationPlanner.butterPrepKey(1L, 2)).isEqualTo("butter-prep-1-2")
        assertThat(BakingNotificationPlanner.stepDoneKey(1L, 2))
            .isNotEqualTo(BakingNotificationPlanner.stepDoneKey(1L, 3))
        assertThat(BakingNotificationPlanner.stepDoneKey(1L, 2))
            .isNotEqualTo(BakingNotificationPlanner.stepDoneKey(2L, 2))
        // Два вида уведомлений об одном шаге не гасят друг друга.
        assertThat(BakingNotificationPlanner.stepDoneKey(1L, 2))
            .isNotEqualTo(BakingNotificationPlanner.butterPrepKey(1L, 2))
    }

    @Test
    fun `the ledger lets a key through once and never again`() {
        val ledger = NotificationLedger()
        assertThat(ledger.markIfNew("step-done-1-0")).isTrue()
        assertThat(ledger.markIfNew("step-done-1-0")).isFalse()
        assertThat(ledger.markIfNew("step-done-1-0")).isFalse()
        // Другой шаг — своё уведомление.
        assertThat(ledger.markIfNew("step-done-1-1")).isTrue()
    }

    @Test
    fun `forgetting a session clears only its own keys`() {
        val ledger = NotificationLedger()
        ledger.markIfNew(BakingNotificationPlanner.stepDoneKey(1L, 0))
        ledger.markIfNew(BakingNotificationPlanner.stepDoneKey(2L, 0))

        ledger.forgetSession(1L)

        assertThat(ledger.markIfNew(BakingNotificationPlanner.stepDoneKey(1L, 0))).isTrue()
        assertThat(ledger.markIfNew(BakingNotificationPlanner.stepDoneKey(2L, 0))).isFalse()
    }
}
