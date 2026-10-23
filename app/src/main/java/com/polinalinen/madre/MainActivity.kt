package com.polinalinen.madre

import com.polinalinen.madre.ui.kit.*
import androidx.compose.ui.graphics.toArgb
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.polinalinen.madre.navigation.MadreNavHost
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.activity.viewModels
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import kotlinx.coroutines.launch
import com.polinalinen.madre.account.yandex.*
import com.polinalinen.madre.config.FeatureFlags
import com.yandex.authsdk.YandexAuthOptions
import com.yandex.authsdk.YandexAuthSdk

/**
 * Каркас: enableEdgeToEdge + разрешение на уведомления + NavHost.
 *
 * Cycle 12: строка хода выпечки в шторке ведёт обратно в книгу, на СВОЮ
 * выпечку — id приезжает в [EXTRA_SESSION_ID]. Активность объявлена
 * singleTop, поэтому тап по уведомлению при живой книге приходит не в
 * onCreate, а в onNewIntent; обрабатываются оба входа.
 */
class MainActivity : ComponentActivity(), ImmersiveHost {
    override val immersiveController = ImmersiveController()
    private val accountEnabled get() = FeatureFlags.YANDEX_ID && BuildConfig.YANDEX_CLIENT_ID.isNotEmpty()
    private val accountModel: YandexAccountViewModel by viewModels {
        ViewModelProvider.AndroidViewModelFactory(application)
    }
    private var yandexBridge: YandexSdkResultBridge? = null

    /**
     * Выпечка, которую попросили открыть. MutableStateFlow, а не разовый
     * колбэк: намерение может прийти раньше, чем NavHost будет готов его
     * исполнить, и должно дождаться.
     */
    private val pendingSessionId = MutableStateFlow<Long?>(null)

    /**
     * Cycle 18: попросили открыть форму кормления — по кнопке «Покормить» из
     * шторки. Ждёт NavHost тем же способом, что и [pendingSessionId].
     */
    private val pendingFeeding = MutableStateFlow(false)
    private val pendingPlanned = MutableStateFlow<com.polinalinen.madre.notifications.PlannedBakeRequest?>(null)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        // Ответ больше не выбрасывается. Отказ — законный выбор: книга
        // запомнит, что спрашивала, и не будет дёргать человека при каждом
        // запуске. Что именно перестаёт работать, честно написано в
        // «Выходных данных» (SettingsScreen), там же и дорога обратно.
        prefs().edit().putBoolean(KEY_NOTIFICATIONS_ASKED, true).putBoolean(KEY_NOTIFICATIONS_GRANTED, granted).apply()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if(accountEnabled) {
            val options=YandexAuthOptions(this,false)
            check(options.clientId == BuildConfig.YANDEX_CLIENT_ID) { "Account login configuration mismatch" }
            yandexBridge=YandexSdkResultBridge(activityResultRegistry,YandexAuthSdk.create(options).contract,
                BuildConfig.YANDEX_CLIENT_ID,accountModel.controller,accountModel.callbackScope,
                savedInstanceState?.getStringArrayList(KEY_YANDEX_REQUESTS).orEmpty())
        }
        enableEdgeToEdge(
            statusBarStyle = androidx.activity.SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { false },
            navigationBarStyle = androidx.activity.SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { false },
        )
        val initial = ReadingPreferences(this).atmosphere.let {
            if (it == Atmosphere.AUTO) atmosphereAt(java.time.LocalTime.now().hour) else it
        }
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(paperAt(initial).bg.toArgb()))
        immersiveController.setPaperLight(initial != Atmosphere.NIGHT)
        immersiveController.attach(window)
        if(savedInstanceState==null)readIntent(intent) else {
            pendingSessionId.value=savedInstanceState.getLong("madre.pending_session",-1).takeIf{it>=0}
            pendingFeeding.value=savedInstanceState.getBoolean("madre.pending_feeding",false)
            val id=savedInstanceState.getString("madre.pending_plan")
            val action=runCatching { com.polinalinen.madre.notifications.PlannedBakeAction.valueOf(savedInstanceState.getString("madre.pending_plan_action").orEmpty()) }.getOrNull()
            if(id!=null && action!=null)pendingPlanned.value=com.polinalinen.madre.notifications.PlannedBakeRequest(id,action)
        }
        askForNotificationsOnce()

        setContent {
            val account=if(accountEnabled)remember(accountModel) {
                YandexAccountUi(accountModel){browser->lifecycleScope.launch{yandexBridge?.launch(browser)}}
            } else null
            CompositionLocalProvider(LocalYandexAccountUi provides account) {
            MadreNavHost(
                pendingSessionId = pendingSessionId,
                pendingFeeding = pendingFeeding,
                pendingPlanned = pendingPlanned,
            )
            }
        }
    }

    override fun onResume() {
        super.onResume(); immersiveController.reapply(window)
        if(accountEnabled)accountModel.retryPendingUnbind()
    }
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) immersiveController.reapply(window)
    }
    override fun onDestroy() { yandexBridge?.close(); immersiveController.detach(window); super.onDestroy() }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        yandexBridge?.let{outState.putStringArrayList(KEY_YANDEX_REQUESTS,it.requestKeysForSavedState())}
        outState.putLong("madre.pending_session",pendingSessionId.value?:-1)
        outState.putBoolean("madre.pending_feeding",pendingFeeding.value)
        outState.putString("madre.pending_plan",pendingPlanned.value?.id)
        outState.putString("madre.pending_plan_action",pendingPlanned.value?.action?.name)
        super.onSaveInstanceState(outState)
    }

    private fun readIntent(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_SESSION_ID, -1L) ?: -1L
        if (id >= 0L) pendingSessionId.value = id
        if (intent?.getBooleanExtra(EXTRA_OPEN_FEEDING, false) == true) pendingFeeding.value = true
        com.polinalinen.madre.notifications.PlannedBakeActions.read(this,intent)?.let{pendingPlanned.value=it}
    }

    /**
     * Разрешение на уведомления спрашивается ОДИН раз. Раньше это делалось
     * при каждом холодном старте: система показывала диалог лишь дважды, а
     * дальше запрос молча проваливался — и книга каждый раз делала вид, что
     * спросила.
     */
    private fun askForNotificationsOnce() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) return
        if (prefs().getBoolean(KEY_NOTIFICATIONS_ASKED, false)) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun prefs() = getSharedPreferences("madre_prefs", Context.MODE_PRIVATE)

    companion object {
        /** Какую выпечку открыть по тапу на уведомление о ходе выпечки. */
        const val EXTRA_SESSION_ID = "madre.session_id"

        /** Открыть форму кормления — приходит с кнопкой «Покормить» из шторки. */
        const val EXTRA_OPEN_FEEDING = "madre.open_feeding"
        const val EXTRA_PLANNED_ID = "madre.planned_id"

        private const val KEY_NOTIFICATIONS_ASKED = "notifications_permission_asked"
        private const val KEY_NOTIFICATIONS_GRANTED = "notifications_permission_granted"
        private const val KEY_YANDEX_REQUESTS = "madre.yandex.request_keys"
    }
}
