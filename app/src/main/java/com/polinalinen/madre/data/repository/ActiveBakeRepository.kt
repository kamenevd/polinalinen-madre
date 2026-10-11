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
