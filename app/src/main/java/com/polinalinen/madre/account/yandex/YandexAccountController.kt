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
    private val family: ForegroundFamilyProvisioning? = null,
    private val onIdentityChanged: (VerifiedYandexProfile?) -> Unit = {},
) : AutoCloseable {
    private val stateLock = Mutex()
    private val apiLock = Mutex()
    private val submissions = ConcurrentHashMap.newKeySet<Job>()
    private val _state = MutableStateFlow<YandexAccountState>(YandexAccountState.SignedOut)
    val state = _state.asStateFlow()
    private val _provisioning = MutableStateFlow(ForegroundProvisioningState())
    val provisioning = _provisioning.asStateFlow()
    // Retained across Activity rotation, but deliberately changes after process death.
    val requestOwnerId: String = UUID.randomUUID().toString()
    @Volatile private var closed = false
    private var sequence = 0L
    private var previousState: YandexAccountState = YandexAccountState.SignedOut
    private val _pendingUnbind = MutableStateFlow(false)
    val pendingUnbind = _pendingUnbind.asStateFlow()
    private var unbindPending: Boolean
        get() = _pendingUnbind.value
        set(value) { _pendingUnbind.value = value }
    private var signInTimeout: Job? = null
    private val initialization = scope.launch {
        try {
            val saved = storage.read()
            stateLock.withLock {
                unbindPending = saved.unbindPending
                if (family != null && saved.foregroundRetryRequired && !saved.unbindPending) {
                    _provisioning.value = ForegroundProvisioningState(
                        ForegroundProvisioningState.Phase.RETRY_REQUIRED, aliceBound = saved.profile != null)
                }
                onIdentityChanged(saved.profile.takeUnless { saved.unbindPending || saved.foregroundRetryRequired })
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
            onIdentityChanged(null)
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
            onIdentityChanged((previousState as? YandexAccountState.SignedIn)?.profile)
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
                withTimeout(if (family == null) BIND_TIMEOUT_MILLIS else PROVISION_TIMEOUT_MILLIS) {
                    val accepted = stateLock.withLock {
                        val active = _state.value as? YandexAccountState.SigningIn
                        if (closed || active?.attempt != attempt || unbindPending) false
                        else {
                            signInTimeout?.cancel()
                            _state.value = YandexAccountState.Binding(attempt)
                            if (family != null) _provisioning.value = ForegroundProvisioningState(
                                ForegroundProvisioningState.Phase.BINDING_ALICE)
                            // Must precede the request: process death cannot reconstruct its bearer.
                            metadata { storage.requireForegroundRetry() }
                            true
                        }
                    }
                    if (!accepted) return@withTimeout
                    // Keep the whole sequence in the same lane as remote unbind.
                    apiLock.withLock {
                        if (!isBinding(attempt)) return@withLock
                        val profile = api.bind(token)
                        if (family != null) {
                            val proceed = stateLock.withLock {
                                if (!isBinding(attempt)) false else {
                                    _provisioning.value = ForegroundProvisioningState(
                                        ForegroundProvisioningState.Phase.AUTHENTICATING_POCKETBASE, aliceBound = true)
                                    metadata { storage.storeAliceBound(profile) }
                                    true
                                }
                            }
                            if (!proceed) return@withLock
                            family.authenticate(token) { isBinding(attempt) }
                            stateLock.withLock {
                                if (isBinding(attempt)) _provisioning.value = _provisioning.value.copy(pocketBaseAuthenticated = true)
                            }
                        }
                        stateLock.withLock {
                            if (isBinding(attempt)) {
                                metadata { storage.storeVerified(profile) }
                                onIdentityChanged(profile)
                                _state.value = YandexAccountState.SignedIn(profile)
                                if (family != null) _provisioning.value = ForegroundProvisioningState(
                                    ForegroundProvisioningState.Phase.COMPLETE, aliceBound = true, pocketBaseAuthenticated = true)
                            }
                        }
                    }
                }
            } catch (_: TimeoutCancellationException) {
                failBinding(attempt, YandexAccountError.SERVER)
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) { failBinding(attempt, YandexAccountError.SERVER) }
                throw cancelled
            } catch (failure: com.polinalinen.madre.integration.v2.ContractFailure) {
                failBinding(attempt, YandexAccountError.SERVER, failure.kind)
            } catch (failure: YandexAccountFailure) { failBinding(attempt, failure.kind) }
            catch (_: Exception) { failBinding(attempt, YandexAccountError.SERVER) }
            finally { token.close() }
        }
        submissions.add(job)
        job.invokeOnCompletion { token.close(); submissions.remove(job) }
        job.start()
        return job
    }

    private fun isBinding(attempt: Long): Boolean = !closed && !unbindPending &&
        (_state.value as? YandexAccountState.Binding)?.attempt == attempt

    private suspend fun failBinding(attempt: Long, kind: YandexAccountError,
        familyError: com.polinalinen.madre.integration.v2.ContractFailure.Kind? = null) {
        stateLock.withLock {
            if (sequence != attempt) return@withLock
            if (_state.value is YandexAccountState.Binding) {
                _state.value = YandexAccountState.Error(kind)
                if (family != null) _provisioning.value = _provisioning.value.copy(
                    phase = ForegroundProvisioningState.Phase.RETRY_REQUIRED, familyError = familyError)
            }
        }
    }

    /** Local logout is immediate; server revocation is only acknowledged after its real reply. */
    suspend fun signOut(onLocalAccountCleared: (VerifiedYandexProfile?) -> Unit = {}) {
        if (closed || !scope.isActive) return
        initialization.join()
        var familyClearFailed = false
        val revocation = stateLock.withLock {
            ++sequence
            signInTimeout?.cancel()
            // A failed SDK launch has never submitted a bearer or enrolled/bound this
            // account. Clearing that local attempt is NOT a remote revocation ACK.
            // Persisted profile/retry/pending (or an in-flight bind) instead requires
            // the old installation credential and a real server ACK, even when lost.
            val binding = _state.value is YandexAccountState.Binding
            var portrait = (_state.value as? YandexAccountState.SignedIn)?.profile
            onIdentityChanged(null)
            _state.value = YandexAccountState.SignedOut
            if (family != null) _provisioning.value = ForegroundProvisioningState()
            // Fence and clear PB credentials/cache before any suspending metadata read.
            // A local PB cleanup failure must not prevent the independent Alice revoke.
            try { family?.signOut() } catch (_: Exception) { familyClearFailed = true }
            val remoteRequired = try {
                val saved = storage.read()
                if (portrait == null) portrait = saved.profile
                binding || unbindPending || saved.profile != null || saved.bindingMetadataPresent || saved.foregroundRetryRequired || saved.unbindPending
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { true } // Unreadable metadata cannot prove never-bound.
            unbindPending = remoteRequired
            previousState = YandexAccountState.SignedOut
            try { storage.clearAccount(unbindPending = remoteRequired) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _state.value = YandexAccountState.Error(YandexAccountError.STORAGE) }
            if (familyClearFailed) _state.value = YandexAccountState.Error(YandexAccountError.STORAGE)
            Triple(sequence, remoteRequired, portrait)
        }
        // The attempt is already fenced and local metadata cleared. The caller
        // never needs a separate suspending read BEFORE logout to find its cache key.
        try { onLocalAccountCleared(revocation.third) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { /* Cache cleanup cannot invent or prevent a remote ACK. */ }
        if (revocation.second) revokePending(revocation.first)
        if (familyClearFailed) stateLock.withLock {
            if (sequence == revocation.first) _state.value = YandexAccountState.Error(YandexAccountError.STORAGE)
        }
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
        onIdentityChanged(null)
        initialization.cancel()
        signInTimeout?.cancel()
        submissions.toList().forEach { it.cancel() }
    }

    companion object {
        const val SIGN_IN_TIMEOUT_MILLIS = 60_000L
        const val BIND_TIMEOUT_MILLIS = 10_000L
        const val PROVISION_TIMEOUT_MILLIS = 30_000L
    }
}
