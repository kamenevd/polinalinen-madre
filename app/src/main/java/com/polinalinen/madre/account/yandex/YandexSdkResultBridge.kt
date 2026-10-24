package com.polinalinen.madre.account.yandex

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.contract.ActivityResultContract
import com.yandex.authsdk.YandexAuthLoginOptions
import com.yandex.authsdk.YandexAuthResult
import com.yandex.authsdk.internal.strategy.LoginType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Register WITHOUT a LifecycleOwner: SDK results must be consumed even while STOPPED,
 * otherwise ActivityResultRegistry may park their raw bearer Intent in a saved Bundle.
 * Saved state contains only random request keys, never SDK results/tokens/profile data.
 * Restore these keys before the Activity can receive results. Retired-process keys are
 * active tombstones; only the retained controller's owner id may submit a result.
 */
class YandexSdkResultBridge(
    private val registry: ActivityResultRegistry,
    private val contract: ActivityResultContract<YandexAuthLoginOptions, YandexAuthResult>,
    private val expectedClientId: String,
    private val controller: YandexAccountController,
    private val scope: CoroutineScope,
    restoredKeys: List<String> = emptyList(),
) : AutoCloseable {
    private val launchers = linkedMapOf<String, ActivityResultLauncher<YandexAuthLoginOptions>>()
    private val completed = mutableSetOf<String>()

    init {
        require(expectedClientId.matches(Regex("[0-9a-fA-F]{32}"))) { "Account login is not configured" }
        restoredKeys.filter { KEY_PATTERN.matches(it) }.forEach(::register)
    }

    /** Called only by an explicit button action, not by restoration or a retry worker. */
    suspend fun launch(browser: Boolean = false) {
        val attempt = controller.beginSignIn(browser) ?: return
        val key = "$PREFIX${controller.requestOwnerId}:$attempt"
        register(key)
        try {
            requireNotNull(launchers[key]).launch(YandexAuthLoginOptions(
                if (browser) LoginType.CHROME_TAB else LoginType.NATIVE, expectedClientId))
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) {
            completed.add(key)
            controller.failSignIn(attempt, YandexAccountError.YANDEX)
            retireCompleted()
        }
    }

    private fun register(key: String) {
        if (key in launchers) return
        val launcher = registry.register(key, contract) { result -> consume(key, result) }
        launchers[key] = launcher
        // register() may synchronously drain a previously pending result.
        retireCompleted()
    }

    private fun consume(key: String, result: YandexAuthResult) {
        val owner = key.removePrefix(PREFIX).substringBeforeLast(':')
        val attempt = key.substringAfterLast(':').toLongOrNull()
        if (owner == controller.requestOwnerId && attempt != null) {
            when (result) {
                is YandexAuthResult.Success -> {
                    if (result.token.clientId != expectedClientId) {
                        scope.launch { controller.failSignIn(attempt, YandexAccountError.FOREIGN_CLIENT) }
                    } else {
                        val token = runCatching { YandexLoginToken.takeSdkResult(result.token.value) }.getOrNull()
                        if (token == null) scope.launch { controller.failSignIn(attempt, YandexAccountError.YANDEX) }
                        else controller.submitSdkToken(attempt, token)
                    }
                }
                is YandexAuthResult.Failure -> scope.launch {
                    // Never retain, stringify or log the SDK exception (it is not UI state).
                    controller.failSignIn(attempt, YandexAccountError.YANDEX)
                }
                YandexAuthResult.Cancelled -> scope.launch { controller.cancelSignIn(attempt) }
            }
        }
        // For a retired owner the raw result simply loses its last app callback reference.
        completed.add(key)
    }

    private fun retireCompleted() {
        completed.toList().forEach { key ->
            launchers.remove(key)?.unregister()
            completed.remove(key)
        }
    }

    fun requestKeysForSavedState(): ArrayList<String> {
        retireCompleted()
        return ArrayList(launchers.keys)
    }

    /** Call only from onDestroy, not onStop; callbacks must stay active through save/STOP. */
    override fun close() {
        launchers.values.forEach { it.unregister() }
        launchers.clear()
        completed.clear()
    }

    private companion object {
        const val PREFIX = "madre.yandex."
        val KEY_PATTERN = Regex("madre\\.yandex\\.[0-9a-f-]{36}:[1-9][0-9]{0,18}")
    }
}
