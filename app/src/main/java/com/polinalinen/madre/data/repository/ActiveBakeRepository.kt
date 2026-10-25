package com.polinalinen.madre.data.repository

import com.polinalinen.madre.data.db.MadreDatabase
import com.polinalinen.madre.data.db.entities.ActiveBakeEntity
import com.polinalinen.madre.data.db.entities.VoiceCommandReceiptEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ActiveBakeRepository(private val db: MadreDatabase) {
    private val dao = db.activeBakeDao()
    private val receipts = db.voiceReceiptDao()

    /** Lifecycle/consent invalidation holds this SAME short lock as the actual DB commit. */
    val commitFence = Any()

    suspend fun all(): List<ActiveBakeEntity> = withContext(Dispatchers.IO) { dao.getAll() }
    suspend fun remember(bake: ActiveBakeEntity) = withContext(Dispatchers.IO) { dao.upsert(bake) }
    suspend fun forget(sessionId: Long) = withContext(Dispatchers.IO) { synchronized(commitFence) { dao.deleteNow(sessionId) } }

    suspend fun receipt(id: String): VoiceCommandReceiptEntity? = withContext(Dispatchers.IO) {
        receipts.getNow(id)
    }

    /** Compare and commit the row and optional receipt, or change nothing. Only the controller calls it. */
    suspend fun commit(
        sessionId: Long,
        previous: ActiveBakeEntity?,
        next: ActiveBakeEntity?,
        receipt: VoiceCommandReceiptEntity? = null,
        preserveUnknownStep: Boolean = false,
        valid: () -> Boolean = { true },
    ): VoiceCommandReceiptEntity? = withContext(Dispatchers.IO) {
        synchronized(commitFence) {
            var result = receipt
            db.runInTransaction {
                check(valid()) { "Controller no longer live" }
                val priorReceipt = receipt?.let { receipts.getNow(it.commandId) }
                if (priorReceipt != null) {
                    check(priorReceipt.fingerprint == receipt.fingerprint) { "Command id payload mismatch" }
                    result = priorReceipt
                } else {
                    val current = dao.getNow(sessionId)
                    check(current?.revision == previous?.revision && current?.bakeKey == previous?.bakeKey) {
                        "Active bake revision changed"
                    }
                    // A negative receipt records an outcome, never a coerced/restored snapshot.
                    if (receipt == null || receipt.outcome == "APPLIED") {
                        if (next == null) dao.deleteNow(sessionId) else {
                            val durable = if (preserveUnknownStep) {
                                checkNotNull(current)
                                next.copy(stepIndex = current.stepIndex, stepTitle = current.stepTitle,
                                    stepDurationMinutes = current.stepDurationMinutes, isWaitStep = current.isWaitStep)
                            } else next
                            dao.upsertNow(durable)
                        }
                    }
                    receipt?.let(receipts::insertNow)
                }
            }
            result
        }
    }

    /** V2 durable lease consumption uses the existing Room12 receipt table, not a second state DB.
     * Namespaced lease row + command row + state/revision are a single SQLite transaction.
     * A lease cannot be reused with another command, even after reconnect/process recreation. */
    suspend fun commitRoster(previous: ActiveBakeEntity?, next: ActiveBakeEntity?,
        receipt: VoiceCommandReceiptEntity, leaseId: String, valid: () -> Boolean): VoiceCommandReceiptEntity = withContext(Dispatchers.IO) {
        synchronized(commitFence) {
            var result = receipt
            rosterTransaction {
                check(valid())
                val oldReceipt = receipts.getNow(receipt.commandId)
                val lease = receipts.getNow("roster-lease:$leaseId")
                if (oldReceipt != null) {
                    check(oldReceipt.fingerprint == receipt.fingerprint && lease?.fingerprint == receipt.fingerprint)
                    result = oldReceipt
                } else {
                    check(lease == null)
                    val current = allNow().singleOrNull { it.bakeKey == receipt.bakeKey }
                    check(current == previous) { "Persisted bake changed" }
                    if (receipt.outcome == "APPLIED") {
                        checkNotNull(previous)
                        check(receipt.expectedRevision == previous.revision && previous.revision < Long.MAX_VALUE)
                        check(receipt.newRevision == previous.revision + 1)
                        if (next == null) dao.deleteNow(previous.sessionId) else {
                            check(next.bakeKey == previous.bakeKey && next.bakeNumber == previous.bakeNumber && next.revision == receipt.newRevision)
                            dao.upsertNow(next)
                        }
                    }
                    receipts.insertNow(receipt)
                    // Sentinel is technical dedup evidence, never a cancellation side effect.
                    receipts.insertNow(receipt.copy(commandId = "roster-lease:$leaseId", operation = "LEASE", effectApplied = true))
                }
                check(valid()) // expiry/disk delay at the final SQL boundary rolls EVERYTHING back
            }
            result
        }
    }

    /** SQLite may auto-rollback on SQLITE_FULL. Room's finally must not replace
     * the primary disk failure with a secondary 'no transaction active' error. */
    private fun rosterTransaction(block: () -> Unit) {
        db.beginTransaction()
        var primary: Throwable? = null
        try { block(); db.setTransactionSuccessful() }
        catch (failure: Throwable) { primary = failure; throw failure }
        finally {
            try { db.endTransaction() }
            catch (cleanup: Throwable) { if (primary == null) throw cleanup else primary.addSuppressed(cleanup) }
        }
    }

    suspend fun initializeLegacyKeys(): List<ActiveBakeEntity> = withContext(Dispatchers.IO) {
        synchronized(commitFence) {
            db.runInTransaction {
                // Only the missing identity is assigned; old scale/recipe digest stay UNKNOWN.
                allNow().filter { it.bakeKey == null }.forEach {
                    dao.upsertNow(it.copy(bakeKey = java.util.UUID.randomUUID().toString()))
                }
            }
            allNow()
        }
    }

    private fun allNow(): List<ActiveBakeEntity> = dao.getAllNow()

    suspend fun applyPendingCancellationEffects(apply: (VoiceCommandReceiptEntity) -> Boolean) =
        withContext(Dispatchers.IO) {
            synchronized(commitFence) {
                receipts.pendingEffectsNow().forEach { if (apply(it)) receipts.markEffectNow(it.commandId) }
                receipts.pruneNow(System.currentTimeMillis() - 86_400_000L)
            }
        }
}
