package com.polinalinen.madre.model

/** UI undo never contains voice authority/receipts/lease state. */
data class BakeUndo(
    val token: String,
    val before: BakingSession,
    val expectedRevision: Long,
    val expiresAtElapsed: Long,
    val kind: Kind,
    val remainingMillis: Long,
    val message: String,
) {
    enum class Kind { START, ADVANCE }
    val sessionId get() = before.id
    val actionLabel get() = if (kind == Kind.START) "Отменить" else "Вернуть"
    fun valid(current: BakingSession?, nowElapsed: Long) = current != null &&
        current.id == before.id && current.bakeKey == before.bakeKey &&
        current.revision == expectedRevision && nowElapsed < expiresAtElapsed

    /** Restore duration consumed, not a fresh full step, and always issue a NEW revision. */
    fun restoredStep(current: BakingSession, nowElapsed: Long, nowWall: Long): BakingSession {
        require(kind == Kind.ADVANCE && valid(current, nowElapsed) && !current.isCompleted)
        val consumed = (before.currentStep.durationMinutes * 60_000L - remainingMillis).coerceAtLeast(0)
        return before.copy(startedAtElapsed = nowElapsed - consumed, startedAtWallClock = nowWall - consumed,
            pausedAtElapsed = if (before.isPaused) nowElapsed else null,
            revision = Math.addExact(current.revision, 1L))
    }
}
