package com.campusmesh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.campusmesh.service.MeshForegroundService
import com.campusmesh.ui.navigation.CampusMeshNavHost
import com.campusmesh.ui.theme.CampusMeshTheme
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Install the Android SplashScreen BEFORE super.onCreate so the OS-level
        // splash (dark background + rounded icon) is shown instead of a blank white frame.
        installSplashScreen()

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Start the persistent BLE mesh service. Wrapped in try-catch to prevent
        // a crash when BLE permissions are not yet granted on first launch —
        // the service will be started again from the PermissionsScreen once granted.
        try {
            MeshForegroundService.startService(this)
        } catch (e: Exception) {
            Timber.w(e, "MeshForegroundService could not start on launch (likely pending permissions). Will retry after grant.")
        }

        setContent {
            CampusMeshTheme {
                CampusMeshNavHost()
            }
        }
    }
}
