package com.polinalinen.madre.sourdough

import android.os.SystemClock
import com.polinalinen.madre.data.db.entities.FeedingEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

data class DiaryBook(val userId: Long, val configId: Long)
data class PendingDiaryDeletion(val token: Long, val book: DiaryBook, val row: FeedingEntity,
    val deadline: Long, val committing: Boolean = false)
data class DiaryDeletionState(val pending: PendingDiaryDeletion? = null, val error: String? = null, val authority: Long = 0)

/** VM-only custody: rotation keeps this instance; VM/process death never schedules future work.
 * Room remains authoritative throughout Undo. Only Journal presentation hides the snapshot.
 */
class DiaryDeletion(
    private val scope: CoroutineScope,
    private val delete: (DiaryBook, FeedingEntity, (() -> Boolean) -> Boolean) -> Boolean,
    private val elapsed: () -> Long = SystemClock::elapsedRealtime,
    private val wait: suspend (Long) -> Unit = { delay(it) },
) {
    private companion object { val tokens = java.util.concurrent.atomic.AtomicLong() }
    private val lock = Any()
    private var book: DiaryBook? = null
    private var generation = 0L
    private var authority = 0L
    private var timer: Job? = null
    private val mutable = MutableStateFlow(DiaryDeletionState())
    val state = mutable.asStateFlow()

    fun selectBook(value: DiaryBook?) = synchronized(lock) {
        if (book != value) { cancelLocked(); book = value }
    }

    fun queue(row: FeedingEntity, expectedAuthority: Long = state.value.authority): Boolean = synchronized(lock) {
        val owner = book ?: return false
        if (!scope.isActive || expectedAuthority != authority || row.sourdoughConfigId != owner.configId || row.id <= 0 || mutable.value.pending != null) return false
        generation = tokens.incrementAndGet()
        val pending = PendingDiaryDeletion(generation, owner, row, elapsed() + 5_000)
        mutable.value = DiaryDeletionState(pending, authority = authority)
        timer = scope.launch {
            try {
                // Recheck monotonic time after wakeup; never let an early wake shorten Undo.
                while (elapsed() < pending.deadline) wait(pending.deadline - elapsed())
                synchronized(lock) {
                    if (!current(pending)) return@launch
                    // The Undo deadline expires even when another feed holds Room.
                    // This is presentation state, not authority to delete after an owner/route change.
                    mutable.value = DiaryDeletionState(pending.copy(committing = true), authority = authority)
                }
                withContext(Dispatchers.IO) {
                    val execution = currentCoroutineContext()[Job]
                    // Acquire Room's transaction first, then our short authority fence:
                    // never block the UI owner transition while awaiting a concurrent feed.
                    val deleted = delete(pending.book, pending.row) { operation ->
                        synchronized(lock) {
                            if (!current(pending) || execution?.isActive != true) false else operation()
                        }
                    }
                    synchronized(lock) {
                        if (current(pending)) mutable.value = if (deleted) DiaryDeletionState(authority = authority) else failure()
                    }
                }
            } catch (cause: CancellationException) { throw cause }
            catch (_: Exception) {
                synchronized(lock) { if (current(pending)) mutable.value = failure() }
            }
        }
        true
    }

    fun undo(token: Long): Boolean = synchronized(lock) {
        val pending = mutable.value.pending ?: return false
        if (token != pending.token || !current(pending) || pending.committing || elapsed() >= pending.deadline) return false
        cancelLocked()
        true
    }

    /** Leaving the visible Diary cancels, not commits. Rotation does not call this. */
    fun leave() = synchronized(lock) { cancelLocked() }
    private fun current(pending: PendingDiaryDeletion) = generation == pending.token && book == pending.book &&
        mutable.value.pending?.token == pending.token
    private fun cancelLocked() {
        generation = tokens.incrementAndGet()
        authority++
        timer?.cancel(); timer = null
        mutable.value = DiaryDeletionState(authority = authority)
    }
    private fun failure() = DiaryDeletionState(error = "Не удалось удалить. Запись сохранена. Повторите удаление.", authority = authority)
}
