package com.campusmesh.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.campusmesh.MainActivity
import com.campusmesh.ble.BleDiscoveryController
import com.campusmesh.transport.DirectTransportController
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service that keeps BLE discovery and GATT connections alive
 * while the app is in the background or the screen is off.
 *
 * This service owns the lifecycle of:
 *  - BLE advertising + scanning (via [BleDiscoveryController])
 *  - GATT server (via [DirectTransportController])
 *  - Periodic mesh self-healing: reconnects to visible peers every ~10 seconds
 *  - Periodic packet flush: drains the outbox every ~2 seconds regardless of
 *    whether clients are currently connected, so new connections immediately
 *    get queued messages.
 */
@AndroidEntryPoint
class MeshForegroundService : Service() {

    @Inject lateinit var discoveryController: BleDiscoveryController
    @Inject lateinit var transportController: DirectTransportController

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Timber.i("MeshForegroundService created")
        try {
            createNotificationChannel()
            startForegroundCompat()
        } catch (e: Exception) {
            Timber.e(e, "Error starting foreground notification in MeshForegroundService")
        }

        try {
            // Start BLE advertising + scanning.
            discoveryController.setForeground(true)
            discoveryController.setWantedRunning(true)

            // Start GATT server so peers can connect to us.
            transportController.startServer()
        } catch (e: Exception) {
            Timber.w(e, "Could not start BLE discovery/GATT server in MeshForegroundService (likely missing permissions)")
        }

        // ── Tick loop: prune stale peers + update notification every second ──
        serviceScope.launch {
            while (isActive) {
                delay(1_000)
                try {
                    discoveryController.tick()
                    updateNotification()
                } catch (e: Exception) {
                    Timber.w(e, "Tick error in MeshForegroundService")
                }
            }
        }

        // ── Continuous packet flush loop (every 2 s) ──────────────────────────
        // Runs even when no peers are connected so new connections immediately
        // drain the outbox without waiting for the next user action.
        serviceScope.launch {
            while (isActive) {
                delay(2_000)
                try {
                    transportController.flushPendingPackets()
                } catch (e: Exception) {
                    Timber.w(e, "Flush error in MeshForegroundService")
                }
            }
        }

        // ── Mesh self-healing loop (every 12 s) ───────────────────────────────
        // Continuously connects to visible BLE peers so messaging works without
        // ever visiting the Peers tab. Stops only when Bluetooth is off.
        serviceScope.launch {
            while (isActive) {
                delay(12_000)
                try {
                    val nearbyPeers = discoveryController.snapshot.value.peers
                    for (peer in nearbyPeers) {
                        val nodeId = peer.nodeId.toString()
                        val addr = peer.deviceAddress
                        if (!transportController.isPeerDirectlyConnected(nodeId) &&
                            !transportController.isAddressDirectlyConnected(addr)
                        ) {
                            Timber.d("MeshService self-heal: connecting to %s @ %s", nodeId, addr)
                            transportController.connectToPeer(addr, nodeId, peer.shortLabel)
                        }
                    }
                } catch (e: Exception) {
                    Timber.w(e, "Self-heal error in MeshForegroundService")
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.i("MeshForegroundService onStartCommand")
        try {
            discoveryController.setWantedRunning(true)
            discoveryController.refresh()
            transportController.onBluetoothRestarted()
        } catch (e: Exception) {
            Timber.w(e, "Error syncing radios in onStartCommand")
        }
        return START_STICKY  // Restart automatically if killed.
    }

    override fun onDestroy() {
        Timber.i("MeshForegroundService destroyed")
        serviceScope.cancel()
        try {
            discoveryController.setForeground(false)
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ──────────────────────────────────────────────────────────
    // Notification
    // ──────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "CampusMesh Mesh",
                NotificationManager.IMPORTANCE_LOW,  // Silent — no sound/vibrate.
            ).apply {
                description = "Keeps the offline mesh network running in the background."
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(peerCount: Int = 0): Notification {
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stopIntent = Intent(this, MeshControlReceiver::class.java).apply {
            action = MeshControlReceiver.ACTION_STOP_MESH
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            this, 1, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val peersText = when (peerCount) {
            0 -> "Scanning for nearby peers…"
            1 -> "1 peer nearby"
            else -> "$peerCount peers nearby"
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CampusMesh")
            .setContentText(peersText)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop Process", stopPendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun startForegroundCompat() {
        val notification = buildNotification(0)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceCompat.startForeground(
                    this,
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Timber.w(e, "startForeground with CONNECTED_DEVICE failed, attempting standard fallback")
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (e2: Exception) {
                Timber.e(e2, "Standard startForeground failed")
            }
        }
    }

    private fun updateNotification() {
        try {
            val peerCount = discoveryController.snapshot.value.peers.size
            val manager = getSystemService(NotificationManager::class.java)
            manager?.notify(NOTIFICATION_ID, buildNotification(peerCount))
        } catch (e: Exception) {
            Timber.w(e, "Failed to update notification")
        }
    }

    companion object {
        private const val CHANNEL_ID = "campusmesh_mesh"
        private const val NOTIFICATION_ID = 1001

        fun startService(context: Context) {
            try {
                val intent = Intent(context, MeshForegroundService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: Exception) {
                Timber.w(e, "Could not start MeshForegroundService")
            }
        }

        fun stopService(context: Context) {
            try {
                context.stopService(Intent(context, MeshForegroundService::class.java))
            } catch (e: Exception) {
                Timber.w(e, "Could not stop MeshForegroundService")
            }
        }
    }
}
