package com.polinalinen.madre.integration.v2

import java.time.Instant

/** Link is explicitly shareable, but never implicitly logged/saved or read from the clipboard. */
class ActiveFamilyInvite(val invitation: FamilyInvitation, val expiresAt: String, val seatsLeft: Int?) {
    override fun toString() = "ActiveFamilyInvite([REDACTED])"
}
/** RAM-only, owner-bound undo receipt; not a credential for SavedState. */
class RemovedFamilyMember(internal val restoreToken: String, val restoreUntil: String, val removalRevision: Long) {
    override fun toString() = "RemovedFamilyMember([REDACTED])"
}
internal fun utc(value: String) { require(value.endsWith("Z")); Instant.parse(value) }

interface FamilyManagementApi {
    suspend fun create(session: FamilySession, name: String): FamilyRoster
    suspend fun update(session: FamilySession, name: String? = null, approvalRequired: Boolean? = null): FamilyRoster
    suspend fun invite(session: FamilySession, rotate: Boolean = false): ActiveFamilyInvite
    suspend fun revokeInvite(session: FamilySession)
    suspend fun renameMember(session: FamilySession, memberId: String, name: String): FamilyRoster
    suspend fun remove(session: FamilySession, memberId: String, rotate: Boolean = true): RemovedFamilyMember
    suspend fun restoreMember(session: FamilySession, memberId: String, receipt: RemovedFamilyMember): FamilyRoster
    suspend fun decide(session: FamilySession, requestId: String, accept: Boolean): FamilyRoster
    suspend fun transfer(session: FamilySession, memberId: String): FamilyRoster
    suspend fun leave(session: FamilySession, newOwnerId: String? = null)
    suspend fun delete(session: FamilySession)
}
