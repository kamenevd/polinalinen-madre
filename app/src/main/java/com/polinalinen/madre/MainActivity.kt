package com.polinalinen.madre

import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import android.Manifest
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.polinalinen.madre.navigation.MadreNavHost
import com.polinalinen.madre.ui.theme.MadreTheme

/**
 * Cycle 0: только каркас — enableEdgeToEdge + разрешение на уведомления + NavHost.
 * Deep-link из уведомлений (SESSION_ID intent extra, см. v3 MainActivity.onNewIntent)
 * добавляется в Cycle 2 вместе с реальным BakingTimerScreen.
 */
class MainActivity : ComponentActivity() {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        setContent {
            MadreTheme {
                MadreNavHost()
            }
        }
    }
}
