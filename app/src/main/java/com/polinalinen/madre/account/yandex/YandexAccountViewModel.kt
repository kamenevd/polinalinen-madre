package com.polinalinen.madre.account.yandex

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.polinalinen.madre.MadreApplication
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import coil.imageLoader
import coil.memory.MemoryCache

/** One Activity-owned account controller; no SavedStateHandle can receive a bearer. */
class YandexAccountViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MadreApplication
    private val storage = app.yandexAccountStorage
    internal val controller = YandexAccountController(viewModelScope, storage, app.yandexAccountApi,
        app.foregroundFamilyProvisioning)
    val state = controller.state
    val provisioning = controller.provisioning
    val pendingUnbind = controller.pendingUnbind
    internal val callbackScope get() = viewModelScope
    private val _offerPending = MutableStateFlow(false)
    val offerPending = _offerPending.asStateFlow()
    private val _offerError = MutableStateFlow<String?>(null)
    val offerError = _offerError.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                val saved = storage.read()
                _offerPending.value = !saved.offerSeen && saved.profile == null &&
                    !saved.foregroundRetryRequired && !saved.unbindPending
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _offerPending.value = false }
        }
        viewModelScope.launch {
            state.collect { account ->
                if (account is YandexAccountState.SignedIn) {
                    _offerPending.value = false
                    try { storage.markOfferSeen() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (_: Exception) { _offerError.value = "Не удалось сохранить настройки входа. Книга остаётся на телефоне." }
                }
            }
        }
    }

    fun skipOffer() {
        // A failed preference write must never trap the user behind optional login.
        _offerPending.value = false
        viewModelScope.launch {
            try {
                storage.markOfferSeen()
                _offerPending.value = false
                _offerError.value = null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { _offerError.value = "Не удалось запомнить выбор. Можно продолжить без входа." }
        }
    }

    /** Called only by the visible warning's button, never by login/bind/SDK callbacks. */
    suspend fun connectAliceFromExplicitTap(bakeKey: String) {
        val signedIn = state.value as? YandexAccountState.SignedIn ?: return
        app.voiceCoordinator.prepareYandexFromExplicitTap(bakeKey) {
            (state.value as? YandexAccountState.SignedIn)?.profile === signedIn.profile
        }
    }

    fun signOut() {
        // Synchronous local authority fence BEFORE any cache/metadata/network suspension.
        app.disconnectVoice()
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) {
            controller.signOut(::clearPortraitCache)
        }
    }

    private fun clearPortraitCache(profile: VerifiedYandexProfile?) {
        profile?.let { cached ->
            try { app.imageLoader.memoryCache?.remove(MemoryCache.Key(cached.avatarCacheKey)) }
            catch (_: Exception) { _offerError.value = "Не удалось удалить портрет из кэша. Выход из аккаунта продолжается." }
            viewModelScope.launch(Dispatchers.IO) {
                try { app.imageLoader.diskCache?.remove(cached.avatarCacheKey) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { _offerError.value = "Не удалось удалить портрет из кэша. Выход из аккаунта продолжается." }
            }
        }
    }

    fun retryPendingUnbind() { viewModelScope.launch { controller.retryPendingUnbind() } }

    override fun onCleared() { controller.close(); super.onCleared() }
}
