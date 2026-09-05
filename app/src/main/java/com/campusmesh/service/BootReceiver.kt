package com.campusmesh.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * Starts MeshForegroundService upon device reboot so background BLE messaging
 * runs continuously without requiring the user to open the app manually.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON"
        ) {
            Timber.i("BootReceiver triggered: starting MeshForegroundService")
            try {
                MeshForegroundService.startService(context)
            } catch (e: Exception) {
                Timber.e(e, "Failed to start MeshForegroundService from BootReceiver")
            }
        }
    }
}
