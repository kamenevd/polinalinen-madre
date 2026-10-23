package com.polinalinen.madre.account.yandex

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID

/** Only a server-verified response may supply a profile. No SDK profile is trusted. */
interface YandexAccountApi {
    suspend fun bind(token: YandexLoginToken): VerifiedYandexProfile
    suspend fun unbind()
}

/** Safe classification only: never retain an HTTP body, SDK exception or bearer in an error. */
class YandexAccountFailure(val kind: YandexAccountError) : Exception("Account request failed: $kind")

/**
 * Foreground-only account state machine. A retry marker is durable, a bearer is not.
 * Attempts fence late SDK/results; the API mutex orders unbind after an in-flight bind.
 * Remote revocation still requires the real server acknowledgement, not this local state.
 */
class YandexAccountController(
    private val scope: CoroutineScope,
    private val storage: YandexAccountStorage,
    private val api: YandexAccountApi,
) : AutoCloseable {
    private val stateLock = Mutex()
    private val apiLock = Mutex()
    private val submissions = ConcurrentHashMap.newKeySet<Job>()
    private val _state = MutableStateFlow<YandexAccountState>(YandexAccountState.SignedOut)
    val state = _state.asStateFlow()
    // Retained across Activity rotation, but deliberately changes after process death.
    val requestOwnerId: String = UUID.randomUUID().toString()
    @Volatile private var closed = false
    private var sequence = 0L
    private var previousState: YandexAccountState = YandexAccountState.SignedOut
    private var unbindPending = false
    private var signInTimeout: Job? = null
    private val initialization = scope.launch {
        try {
            val saved = storage.read()
            stateLock.withLock {
                unbindPending = saved.unbindPending
                _state.value = when {
                    saved.unbindPending -> YandexAccountState.SignedOut
                    saved.foregroundRetryRequired -> YandexAccountState.Error(YandexAccountError.SERVER)
                    saved.profile != null -> YandexAccountState.SignedIn(saved.profile)
                    else -> YandexAccountState.SignedOut
                }
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { _state.value = YandexAccountState.Error(YandexAccountError.STORAGE) }
    }

    /** Returns a non-secret request id, never an automatic SDK launch. */
    suspend fun beginSignIn(browser: Boolean = false): Long? {
        if (closed || !scope.isActive) return null
        initialization.join()
        return stateLock.withLock {
            if (closed || _state.value is YandexAccountState.Binding || unbindPending) return@withLock null
            signInTimeout?.cancel()
            if (_state.value !is YandexAccountState.SigningIn) {
                previousState = (_state.value as? YandexAccountState.SignedIn) ?: YandexAccountState.SignedOut
            }
            val attempt = ++sequence
            _state.value = YandexAccountState.SigningIn(attempt, browser)
            signInTimeout = scope.launch {
                delay(SIGN_IN_TIMEOUT_MILLIS)
                cancelSignIn(attempt)
            }
            attempt
        }
    }

    suspend fun cancelSignIn(attempt: Long) {
        stateLock.withLock {
            val active = _state.value as? YandexAccountState.SigningIn ?: return@withLock
            if (active.attempt != attempt) return@withLock
            ++sequence
            signInTimeout?.cancel()
            _state.value = previousState
        }
    }

    suspend fun failSignIn(attempt: Long, kind: YandexAccountError) {
        stateLock.withLock {
            val active = _state.value as? YandexAccountState.SigningIn ?: return@withLock
            if (active.attempt != attempt) return@withLock
            ++sequence
            signInTimeout?.cancel()
            _state.value = YandexAccountState.Error(kind)
        }
    }

    /** Takes ownership even for stale results or an already-cancelled owner scope. */
    fun submitSdkToken(attempt: Long, token: YandexLoginToken): Job {
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                withTimeout(BIND_TIMEOUT_MILLIS) {
                    val accepted = stateLock.withLock {
                        val active = _state.value as? YandexAccountState.SigningIn
                        if (closed || active?.attempt != attempt || unbindPending) false
                        else {
                            signInTimeout?.cancel()
                            _state.value = YandexAccountState.Binding(attempt)
                            // Must precede the request: process death cannot reconstruct its bearer.
                            metadata { storage.requireForegroundRetry() }
                            true
                        }
                    }
                    if (!accepted) return@withTimeout
                    val profile = apiLock.withLock { api.bind(token) }
                    stateLock.withLock {
                        val active = _state.value as? YandexAccountState.Binding
                        if (sequence == attempt && active?.attempt == attempt && !unbindPending) {
                            metadata { storage.storeVerified(profile) }
                            _state.value = YandexAccountState.SignedIn(profile)
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                failBinding(attempt, YandexAccountError.SERVER)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: YandexAccountFailure) { failBinding(attempt, failure.kind) }
            catch (_: Exception) { failBinding(attempt, YandexAccountError.SERVER) }
            finally { token.close() }
        }
        submissions.add(job)
        job.invokeOnCompletion { token.close(); submissions.remove(job) }
        job.start()
        return job
    }

    private suspend fun failBinding(attempt: Long, kind: YandexAccountError) {
        stateLock.withLock {
            if (sequence != attempt) return@withLock
            if (_state.value is YandexAccountState.Binding) _state.value = YandexAccountState.Error(kind)
        }
    }

    /** Local logout is immediate; server revocation is only acknowledged after its real reply. */
    suspend fun signOut() {
        if (closed || !scope.isActive) return
        initialization.join()
        val generation = stateLock.withLock {
            ++sequence
            signInTimeout?.cancel()
            unbindPending = true
            _state.value = YandexAccountState.SignedOut
            try { storage.clearAccount(unbindPending = true) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.value = YandexAccountState.Error(YandexAccountError.STORAGE) }
            sequence
        }
        revokePending(generation)
    }

    suspend fun retryPendingUnbind() {
        if (closed || !scope.isActive) return
        initialization.join()
        val generation = stateLock.withLock { if (unbindPending) sequence else return }
        revokePending(generation)
    }

    private suspend fun revokePending(generation: Long) {
        try {
            apiLock.withLock { withTimeout(BIND_TIMEOUT_MILLIS) { api.unbind() } }
            stateLock.withLock {
                if (sequence == generation && unbindPending) {
                    // Also retry a local clear that may have failed before the network call.
                    metadata { storage.clearAccount(unbindPending = false) }
                    unbindPending = false
                    _state.value = YandexAccountState.SignedOut
                }
            }
        } catch (cancelled: CancellationException) {
            if (cancelled !is TimeoutCancellationException) throw cancelled
            // No acknowledgement: keep only the durable unbind marker, never credentials.
        } catch (failure: YandexAccountFailure) {
            if (failure.kind == YandexAccountError.STORAGE) {
                stateLock.withLock { if(sequence == generation) _state.value = YandexAccountState.Error(YandexAccountError.STORAGE) }
            }
        } catch (_: Exception) { /* Deliberately remains pending, not a fictitious success. */ }
    }

    private suspend fun <T> metadata(block: suspend () -> T): T = try { block() }
    catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { throw YandexAccountFailure(YandexAccountError.STORAGE) }

    override fun close() {
        closed = true
        initialization.cancel()
        signInTimeout?.cancel()
        submissions.toList().forEach { it.cancel() }
    }

    companion object {
        const val SIGN_IN_TIMEOUT_MILLIS = 60_000L
        const val BIND_TIMEOUT_MILLIS = 10_000L
    }
}
