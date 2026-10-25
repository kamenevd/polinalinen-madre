package com.polinalinen.madre.integration.v2

import org.junit.Assert.*
import org.junit.Test

class RosterV2Test {
    private val connection="00000000-0000-0000-0000-000000000001"
    private val key="00000000-0000-0000-0000-000000000002"
    private fun bake()=RosterBake(key,2,7,"recipe-v1","Хлеб",RosterBakeState.RUNNING,0,2,"Опара",60,portions=1,controlReady=true)
    private fun command()=RosterCommand("00000000-0000-0000-0000-000000000003",connection,key,2,7,"recipe-v1",RosterOperation.PAUSE,1000,4000,connection,0,false)
    private fun decision(command: RosterCommand=command(), bakes: List<RosterBake> = listOf(bake()), consent: Boolean=true, attached: Boolean=true,
                         server: Long=1000, received: Long=10, elapsed: Long=20, wall: Long=1010) =
        RosterCommandPolicy.check(command,BakeRoster(connection,4,bakes),consent,attached,server,received,elapsed,wall)

    @Test fun eligibleIsOnlyCommitProposalNotAnAppliedReceipt() {
        assertEquals(RosterCommandPolicy.Decision.ELIGIBLE_FOR_COMMIT,decision())
        assertEquals(7L,bake().revision)
    }
    @Test fun numberReuseCannotRetargetStableBakeKeyOrRevision() {
        val other=bake().copy(bakeKey="00000000-0000-0000-0000-000000000004")
        assertEquals(RosterCommandPolicy.Decision.STALE,decision(bakes=listOf(other)))
        assertEquals(RosterCommandPolicy.Decision.STALE,decision(bakes=listOf(bake().copy(revision=8))))
        assertEquals(RosterCommandPolicy.Decision.STALE,decision(bakes=listOf(bake().copy(bakeNumber=3))))
        assertEquals(RosterCommandPolicy.Decision.STALE,decision(bakes=emptyList()))
        assertEquals(RosterCommandPolicy.Decision.STALE,decision(command().copy(recipeRevision="recipe-v2")))
        assertEquals(RosterCommandPolicy.Decision.STALE,decision(command().copy(expectedStep=1)))
        assertEquals(RosterCommandPolicy.Decision.STALE,decision(command().copy(expectedPaused=true)))
    }
    @Test fun plannedAndFinaleAreReadOnlyForEveryOperation() {
        for (state in listOf(RosterBakeState.SCHEDULED,RosterBakeState.FINALE)) for(operation in RosterOperation.entries) {
            val bake=bake().copy(state=state,startAt=if(state==RosterBakeState.SCHEDULED)5000 else null,controlReady=false)
            assertEquals(RosterCommandPolicy.Decision.READ_ONLY,decision(command().copy(operation=operation),listOf(bake)))
        }
    }
    @Test fun installationConsentAndConnectionCannotBeInferredFromLegacyGrant() {
        assertEquals(RosterCommandPolicy.Decision.REJECTED,decision(consent=false))
        assertEquals(RosterCommandPolicy.Decision.REJECTED,decision(attached=false))
        assertEquals(RosterCommandPolicy.Decision.REJECTED,decision(command().copy(connectionId=key)))
        assertEquals(RosterCommandPolicy.Decision.REJECTED,decision(command().copy(operation=RosterOperation.RESUME)))
    }
    @Test fun expiryUsesMonotonicAgeAndWallAndNeverExtendsServerLease() {
        assertEquals(RosterCommandPolicy.Decision.EXPIRED,decision(elapsed=9))
        assertEquals(RosterCommandPolicy.Decision.EXPIRED,decision(elapsed=4010))
        assertEquals(RosterCommandPolicy.Decision.EXPIRED,decision(wall=4000))
        assertEquals(RosterCommandPolicy.Decision.EXPIRED,decision(server=0))
        assertEquals(RosterCommandPolicy.Decision.EXPIRED,decision(server=Long.MAX_VALUE))
        assertEquals(RosterCommandPolicy.Decision.EXPIRED,decision(wall=999))
    }
    @Test fun rosterAllowsEmptyReplacementButRejectsDuplicatesAndTenthBake() {
        assertTrue(BakeRoster(connection,0,emptyList()).bakes.isEmpty())
        assertTrue(runCatching { BakeRoster(connection,0,listOf(bake(),bake())) }.isFailure)
        assertTrue(runCatching { BakeRoster(connection,0,listOf(bake(),bake().copy(bakeKey=connection))) }.isFailure)
        val nine=(1..9).map { bake().copy(bakeKey="00000000-0000-0000-0000-00000000000$it",bakeNumber=it) }
        assertEquals(9,BakeRoster(connection,1,nine).bakes.size)
        assertTrue(runCatching { BakeRoster(connection,1,nine+bake()) }.isFailure)
    }
    @Test fun rosterSnapshotsCallerListAndWireNamesRemainExplicit() {
        val source=mutableListOf(bake());val roster=BakeRoster(connection,0,source);source.clear()
        assertEquals(1,roster.bakes.size);assertEquals("running",roster.payload().getAsJsonArray("bakes").single().asJsonObject.string("state"))
    }
    @Test fun domainRefusesInvalidNumbersTimesAndRevisions() {
        assertTrue(runCatching { bake().copy(bakeNumber=0) }.isFailure)
        assertTrue(runCatching { bake().copy(bakeNumber=10) }.isFailure)
        assertTrue(runCatching { bake().copy(revision=-1) }.isFailure)
        assertTrue(runCatching { bake().copy(state=RosterBakeState.SCHEDULED) }.isFailure)
        assertTrue(runCatching { bake().copy(startAt=5000) }.isFailure)
        assertTrue(runCatching { command().copy(expiresAt=5001) }.isFailure)
        assertTrue(runCatching { command().copy(expectedRevision=-1) }.isFailure)
    }
}
