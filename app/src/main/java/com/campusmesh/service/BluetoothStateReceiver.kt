package com.campusmesh.service

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

/**
 * BroadcastReceiver listening for Bluetooth state changes.
 *
 * When the user turns Bluetooth ON (even if the app is closed or not in memory),
 * this receiver wakes MeshForegroundService to resume BLE advertising, scanning,
 * and flush any queued outbound mesh packets.
 */
class BluetoothStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
            val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
            if (state == BluetoothAdapter.STATE_ON) {
                Timber.i("Bluetooth turned ON: waking MeshForegroundService and syncing mesh")
                try {
                    MeshForegroundService.startService(context)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to start MeshForegroundService on Bluetooth STATE_ON")
                }
            }
        }
    }
}
