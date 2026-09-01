package com.campusmesh

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campusmesh.service.MeshForegroundService
import com.campusmesh.ui.navigation.CampusMeshNavHost
import com.campusmesh.ui.theme.CampusMeshTheme
import com.campusmesh.ui.theme.ThemeManager
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var themeManager: ThemeManager

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        try {
            MeshForegroundService.startService(this)
        } catch (e: Exception) {
            Timber.w(e, "MeshForegroundService could not start on launch")
        }

        setContent {
            val currentTheme by themeManager.currentTheme.collectAsStateWithLifecycle()

            CampusMeshTheme(appTheme = currentTheme) {
                CampusMeshNavHost()
            }
        }
    }
}
