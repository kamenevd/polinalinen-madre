package com.polinalinen.madre.voice

import com.polinalinen.madre.model.*
import org.junit.Assert.*
import org.junit.Test

class VoiceCommandPolicyTest {
    private val bake = "00000000-0000-4000-8000-000000000001"
    private val generation = "00000000-0000-4000-8000-000000000002"
    private val connection = "00000000-0000-4000-8000-000000000003"
    private val digest = "a".repeat(64)
    private fun session() = BakingSession(7,
        Recipe("bread", "Хлеб", "", "", emptyMap(), listOf(
            TimelineStep(StepType.WAIT, "Расстойка", "", 60),
            TimelineStep(StepType.ACTION, "Выпечка", "", 20))),
        startedAtElapsed = 10_000L, startedAtWallClock = 100_000L,
        scaleFactor = 3.0, bakeKey = bake, revision = 5, recipeRevision = digest)
    private fun command(op: VoiceOperation = VoiceOperation.PAUSE) = VoiceCommand(
        "00000000-0000-4000-8000-000000000004", op, bake, digest, generation, connection,
        5, 0, false, 100_000, 104_000)
    private fun authority() = VoiceAuthority(true, true, bake, generation, connection, digest, 100_900)
    private fun decide(c: VoiceCommand = command(), s: BakingSession = session(),
                       a: VoiceAuthority = authority(), wall: Long = 101_000L,
                       elapsed: Long = 11_000L, received: Long = 10_900L) =
        VoiceCommandPolicy.decide(c, s, a, elapsed, wall, received)

    @Test fun pauseProducesCandidateNotMutatingSource() {
        val s = session(); val r = decide(s = s)
        assertEquals(VoiceOutcome.APPLIED, r.outcome)
        assertTrue(r.candidate!!.isPaused); assertFalse(s.isPaused)
        assertEquals(6, r.candidate.revision); assertEquals(3, r.candidate.trustedPortions)
    }
    @Test fun lastStepVoiceNeverCompletesOrReturnsCandidate() {
        val r = decide(command(VoiceOperation.ADVANCE).copy(expectedStep = 1), session().copy(currentStepIndex = 1))
        assertEquals(VoiceOutcome.PHONE_REQUIRED, r.outcome); assertNull(r.candidate); assertFalse(r.removesBake)
    }
    @Test fun serverClockExpiresCommandEvenWhenPhoneWallLags() {
        assertEquals(VoiceOutcome.EXPIRED, decide(a = authority().copy(serverTimeAtReceipt = 104_000), wall = 100_101).outcome)
        assertEquals(VoiceOutcome.EXPIRED, decide(a = authority().copy(serverTimeAtReceipt = 103_950)).outcome)
        assertEquals(VoiceOutcome.EXPIRED, decide(a = authority().copy(serverTimeAtReceipt = 0)).outcome)
    }
    @Test fun expiredFutureAndMonotonicAgeFailClosed() {
        assertEquals(VoiceOutcome.EXPIRED, decide(wall = 104_000).outcome)
        assertEquals(VoiceOutcome.EXPIRED, decide(wall = 99_999).outcome)
        assertEquals(VoiceOutcome.EXPIRED, decide(received = 7_000).outcome)
        assertEquals(VoiceOutcome.EXPIRED, decide(received = 12_000).outcome)
        assertEquals(VoiceOutcome.EXPIRED, decide(command().copy(expiresAt = 105_000)).outcome)
    }
    @Test fun everyAuthorityAndExpectedStateFenceIsRequired() {
        for (a in listOf(authority().copy(consented = false), authority().copy(attached = false),
            authority().copy(connectionId = bake), authority().copy(selectionGeneration = bake),
            authority().copy(bakeKey = connection))) assertEquals(VoiceOutcome.REJECTED, decide(a = a).outcome)
        for (c in listOf(command().copy(expectedRevision = 4), command().copy(expectedStep = 1),
            command().copy(expectedPaused = true))) assertEquals(VoiceOutcome.STALE, decide(c).outcome)
    }
    @Test fun unknownScaleRecipeOrInvalidRestoredStepNeverControls() {
        for (s in listOf(session().copy(scaleFactor = null), session().copy(recipeRevision = null),
            session().copy(recipeRevision = "b".repeat(64)), session().copy(restoredStepValid = false),
            session().copy(scaleFactor = Double.NaN), session().copy(scaleFactor = 1.2))) {
            assertEquals(VoiceOutcome.REJECTED, decide(s = s).outcome)
        }
    }
    @Test fun canceledCandidateRemovesOnlyAfterAppliedDecision() {
        val r = decide(command(VoiceOperation.CANCEL))
        assertTrue(r.removesBake); assertNull(r.candidate)
        assertFalse(decide(command(VoiceOperation.CANCEL), a = authority().copy(attached = false)).removesBake)
    }
    @Test fun repeatedWrongPauseAndFirstStepBackReject() {
        assertEquals(VoiceOutcome.REJECTED, decide(command(VoiceOperation.RESUME)).outcome)
        assertEquals(VoiceOutcome.REJECTED, decide(command(VoiceOperation.BACK)).outcome)
        assertEquals(VoiceOutcome.REJECTED, decide(command().copy(expectedPaused = true), session().copy(isPaused = true)).outcome)
    }
    @Test fun malformedIdentityAndOverflowReject() {
        assertEquals(VoiceOutcome.REJECTED, decide(command().copy(id = "1-1-1-1-1")).outcome)
        assertEquals(VoiceOutcome.REJECTED, decide(command().copy(expectedRevision = Long.MAX_VALUE), session().copy(revision = Long.MAX_VALUE)).outcome)
    }
    @Test fun commandIdentityFingerprintDoesNotCollapseNewUtterances() {
        val c = command()
        assertEquals(c.fingerprint(), c.copy().fingerprint())
        assertNotEquals(c.fingerprint(), c.copy(operation = VoiceOperation.CANCEL).fingerprint())
        assertNotEquals(c.fingerprint(), c.copy(id = connection).fingerprint())
    }
    @Test fun legacyRestoreDoesNotInventScaleOrRecipeIdentity() {
        val s = BakingSession.restoreFromActive(7, session().recipe, 0, 100_000, null, 11_000, 101_000)
        assertNull(s.scaleFactor); assertNull(s.trustedPortions); assertNull(s.recipeRevision)
        assertNull(s.rebasedTo(0, 102_000).trustedPortions)
    }
    @Test fun trustedScaleAndIdentitySurviveRebaseAndRestore() {
        val s = BakingSession.restoreFromActive(7, session().recipe, 0, 100_000, null, 11_000, 101_000,
            scaleFactor = 5.0, bakeKey = bake, revision = 9, recipeRevision = digest)
        assertEquals(5, s.trustedPortions); assertEquals(bake, s.bakeKey); assertEquals(9, s.revision)
        assertEquals(digest, s.recipeRevision); assertEquals(3599, s.remainingSeconds(11_000))
    }
    @Test fun invalidRestoredStepIsFlaggedEvenWithClampedLocalDisplay() {
        val s = BakingSession.restoreFromActive(7, session().recipe, 99, 100_000, null, 11_000, 101_000)
        assertEquals(1, s.currentStepIndex); assertFalse(s.restoredStepValid)
    }
}
