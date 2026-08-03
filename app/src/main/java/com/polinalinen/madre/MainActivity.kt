package com.polinalinen.madre

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
import com.polinalinen.madre.ui.theme.MadreTheme
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Каркас: enableEdgeToEdge + разрешение на уведомления + NavHost.
 *
 * Cycle 12: строка хода выпечки в шторке ведёт обратно в книгу, на СВОЮ
 * выпечку — id приезжает в [EXTRA_SESSION_ID]. Активность объявлена
 * singleTop, поэтому тап по уведомлению при живой книге приходит не в
 * onCreate, а в onNewIntent; обрабатываются оба входа.
 */
class MainActivity : ComponentActivity() {

    /**
     * Выпечка, которую попросили открыть. MutableStateFlow, а не разовый
     * колбэк: намерение может прийти раньше, чем NavHost будет готов его
     * исполнить, и должно дождаться.
     */
    private val pendingSessionId = MutableStateFlow<Long?>(null)

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
        enableEdgeToEdge()
        readSessionFrom(intent)
        askForNotificationsOnce()

        setContent {
            MadreTheme {
                MadreNavHost(pendingSessionId = pendingSessionId)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readSessionFrom(intent)
    }

    private fun readSessionFrom(intent: Intent?) {
        val id = intent?.getLongExtra(EXTRA_SESSION_ID, -1L) ?: -1L
        if (id >= 0L) pendingSessionId.value = id
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

        private const val KEY_NOTIFICATIONS_ASKED = "notifications_permission_asked"
        private const val KEY_NOTIFICATIONS_GRANTED = "notifications_permission_granted"
    }
}
