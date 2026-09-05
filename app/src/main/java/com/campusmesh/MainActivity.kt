package com.campusmesh

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.campusmesh.service.MeshForegroundService
import com.campusmesh.ui.navigation.CampusMeshNavHost
import com.campusmesh.ui.theme.CampusMeshTheme
import com.campusmesh.ui.theme.ThemeManager
import dagger.hilt.android.AndroidEntryPoint
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.campusmesh.call.CallManager
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var themeManager: ThemeManager
    @Inject lateinit var callManager: CallManager

    private val pendingNavigationRoute = mutableStateOf<String?>(null)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        Timber.i("Permission results: %s", results)
        try {
            MeshForegroundService.startService(this)
        } catch (e: Exception) {
            Timber.w(e, "Could not start MeshForegroundService after permission check")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()

        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestRequiredPermissions()

        handleIntent(intent)

        try {
            MeshForegroundService.startService(this)
        } catch (e: Exception) {
            Timber.w(e, "MeshForegroundService could not start on launch")
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                callManager.callState.collect { state ->
                    if (state is com.campusmesh.call.CallState.Incoming) {
                        val route = com.campusmesh.ui.navigation.Routes.call(state.peerNodeId, state.peerName, isIncoming = true)
                        if (pendingNavigationRoute.value != route) {
                            pendingNavigationRoute.value = route
                        }
                    }
                }
            }
        }

        setContent {
            val currentTheme by themeManager.currentTheme.collectAsStateWithLifecycle()

            CampusMeshTheme(appTheme = currentTheme) {
                CampusMeshNavHost(
                    pendingRoute = pendingNavigationRoute.value,
                    onRouteConsumed = { pendingNavigationRoute.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val navRoute = intent?.getStringExtra("navigate_route")
        if (intent?.getBooleanExtra("auto_accept_call", false) == true) {
            callManager.acceptCall()
        }
        if (!navRoute.isNullOrBlank()) {
            pendingNavigationRoute.value = navRoute
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissions.add(Manifest.permission.RECORD_AUDIO)

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }
}
