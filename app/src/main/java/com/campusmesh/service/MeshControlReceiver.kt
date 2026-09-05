package com.campusmesh.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * Handles the "Stop Mesh" action broadcast from the foreground service notification.
 * When the user taps the action button, the mesh service is stopped.
 * The next time the app is opened, [com.campusmesh.MainActivity] restarts the service automatically.
 */
class MeshControlReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_STOP_MESH) {
            Timber.i("MeshControlReceiver: stopping mesh service on user request")
            MeshForegroundService.stopService(context)
        }
    }

    companion object {
        const val ACTION_STOP_MESH = "com.campusmesh.ACTION_STOP_MESH"
    }
}
