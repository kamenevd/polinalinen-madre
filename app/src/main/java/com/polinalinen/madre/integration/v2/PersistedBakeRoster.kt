package com.polinalinen.madre.integration.v2

import com.polinalinen.madre.data.db.entities.ActiveBakeEntity
import com.polinalinen.madre.model.BakingSession
import com.polinalinen.madre.data.repository.activeSnapshot
import com.polinalinen.madre.model.Recipe
import com.polinalinen.madre.model.StepType
import com.polinalinen.madre.navigation.BakeNumberStore
import com.polinalinen.madre.navigation.BookPreferences
import com.polinalinen.madre.voice.voiceSnapshot

/** Caller holds Application operation gate -> controller transition gate. Reads never repair data. */
object PersistedBakeRoster {
    fun session(row: ActiveBakeEntity, recipes: List<Recipe>, digest: String, elapsed: Long, wall: Long, live: BakingSession? = null): BakingSession? {
        val recipe = recipes.singleOrNull { it.id == row.recipeId } ?: return null
        if (recipe.timeline.isEmpty() || row.bakeKey == null || !uuid(row.bakeKey) || row.revision < 0) return null
        val step = recipe.timeline.getOrNull(row.stepIndex)
        val trusted = row.recipeRevision == digest && step != null && step.title == row.stepTitle &&
            row.recipeName == recipe.name && row.isWaitStep == (step.type == StepType.WAIT) &&
            row.stepDurationMinutes >= 0 && row.startedAtWallClock > 0 && wall >= row.startedAtWallClock &&
            (row.pausedAtWallClock == null || row.pausedAtWallClock in row.startedAtWallClock..wall)
        val current = if (trusted) recipe.copy(timeline = recipe.timeline.mapIndexed { i, s ->
            if (i == row.stepIndex) s.copy(durationMinutes = row.stepDurationMinutes) else s
        }) else recipe
        // The matching live session owns monotonic time. A wall-clock adjustment must
        // not silently restart/re-time an already running phone timer from its wall row.
        if (live != null && live.activeSnapshot() == row) return live.copy(recipe = current, restoredStepValid = trusted)
        return BakingSession.restoreFromActive(row.sessionId, current, row.stepIndex, row.startedAtWallClock,
            row.pausedAtWallClock, elapsed, wall, row.scaleFactor, row.bakeKey, row.revision,
            row.recipeRevision, row.bakeNumber).copy(restoredStepValid = trusted)
    }

    fun active(row: ActiveBakeEntity, recipes: List<Recipe>, digest: String, elapsed: Long, wall: Long, live: BakingSession? = null): RosterBake {
        val session = requireNotNull(session(row, recipes, digest, elapsed, wall, live))
        val snapshot = session.voiceSnapshot(elapsed, wall, digest)
        return RosterBake(session.bakeKey, row.bakeNumber, row.revision, row.recipeRevision ?: "unknown",
            row.recipeName, if (session.isPaused) RosterBakeState.PAUSED else RosterBakeState.RUNNING,
            session.currentStepIndex, session.recipe.timeline.size, row.stepTitle,
            session.remainingSeconds(elapsed).coerceAtMost(604800),
            stepText = (snapshot["step_text"] as? String)?.wireText(), nextStepTitle = (snapshot["next_step_title"] as? String)?.wireText(),
            nextStepText = (snapshot["next_step_text"] as? String)?.wireText(), quantities = (snapshot["quantities"] as? String)?.wireText(),
            portions = snapshot["portions"] as? Int, controlReady = snapshot["control_ready"] == true)
    }

    fun read(connection: String, revision: Long, rows: List<ActiveBakeEntity>, book: BookPreferences,
        finales: List<BakeNumberStore.Finale>, recipes: List<Recipe>, digest: String, elapsed: Long, wall: Long, live: List<BakingSession> = emptyList()): BakeRoster {
        val active = rows.map { row -> active(row, recipes, digest, elapsed, wall, live.singleOrNull { it.id == row.sessionId }) }
        val plans = book.snapshotPlans().mapNotNull { plan ->
            val identity = book.voicePlanIdentity(plan.id)
            if (active.any { it.bakeKey == identity.first }) null else {
                val recipe = requireNotNull(recipes.singleOrNull { it.id == plan.recipeId })
                RosterBake(identity.first, plan.number, identity.second, "unknown", recipe.name,
                    RosterBakeState.SCHEDULED, 0, recipe.timeline.size, "Запланировано", 0, plan.startAtMillis)
            }
        }
        val finished = finales.filter { f -> rows.none { it.sessionId == f.sessionId } }.map { f ->
            RosterBake(f.bakeKey ?: java.util.UUID.nameUUIDFromBytes("legacy-finale:${f.sessionId}".toByteArray()).toString(),
                f.number, f.revision, f.recipeRevision ?: "unknown", f.recipeName, RosterBakeState.FINALE,
                0, 1, "Испечено · впишите в книгу на телефоне", 0)
        }
        return BakeRoster(connection, revision, (active + plans + finished).sortedBy { it.bakeNumber })
    }

    // Producer text allowlist rejects controls/newlines. Never send unbounded recipe data.
    private fun String.wireText(): String? = replace(Regex("\\s+"), " ").trim().takeIf { it.isNotEmpty() && it.length <= 2048 }
}
