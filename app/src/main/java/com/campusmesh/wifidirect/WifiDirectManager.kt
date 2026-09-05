package com.campusmesh.wifidirect

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.NetworkInfo
import android.net.Uri
import android.net.wifi.WpsInfo
import android.net.wifi.p2p.*
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
import java.io.*
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

/** Richer peer data shown in AirDrop UI: includes avatar from BLE identity exchange. */
data class WifiDirectDevice(
    val name: String,
    val address: String,
    val status: String,
    val avatarPath: String? = null,
    val rssi: Int = -70,     // -30 = very close, -90 = far
)

data class TransferProgress(
    val isTransferring: Boolean = false,
    val isReceiving: Boolean = false,
    val fileName: String = "",
    val bytesTransferred: Long = 0L,
    val totalBytes: Long = 0L,
    val isComplete: Boolean = false,
    val receivedFilePath: String? = null,
    val error: String? = null,
    val logLines: List<String> = emptyList(),
)

@Singleton
class WifiDirectManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var wifiP2pManager: WifiP2pManager? = null
    private var channel: WifiP2pManager.Channel? = null
    private var isReceiverRegistered = false

    private val _discoveredPeers = MutableStateFlow<List<WifiDirectDevice>>(emptyList())
    val discoveredPeers: StateFlow<List<WifiDirectDevice>> = _discoveredPeers.asStateFlow()

    private val _connectionInfo = MutableStateFlow<WifiP2pInfo?>(null)
    val connectionInfo: StateFlow<WifiP2pInfo?> = _connectionInfo.asStateFlow()

    private val _transferProgress = MutableStateFlow(TransferProgress())
    val transferProgress: StateFlow<TransferProgress> = _transferProgress.asStateFlow()

    private val _isWifiP2pEnabled = MutableStateFlow(false)
    val isWifiP2pEnabled: StateFlow<Boolean> = _isWifiP2pEnabled.asStateFlow()

    private val _isReceiveModeActive = MutableStateFlow(false)
    val isReceiveModeActive: StateFlow<Boolean> = _isReceiveModeActive.asStateFlow()

    /** Exposed so AirDropOverlay can update peer avatars once BLE identity is known. */
    private val peerAvatarMap = mutableMapOf<String, String?>()   // deviceName -> avatarPath

    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null

    /** Optional auto-connect callback: called when a peer's signal strength crosses close threshold. */
    private var pendingAutoSend: ((WifiDirectDevice) -> Unit)? = null

    init {
        try {
            wifiP2pManager = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
            channel = wifiP2pManager?.initialize(context, context.mainLooper, null)
            registerReceiver()
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize WifiP2pManager")
        }
    }

    /** Call this from AirDropOverlay with the known BLE-resolved avatar map so node circles show avatars. */
    fun updatePeerAvatars(avatarsByName: Map<String, String?>) {
        peerAvatarMap.putAll(avatarsByName)
        // refresh current peer list with updated avatars
        _discoveredPeers.update { peers ->
            peers.map { peer ->
                peer.copy(avatarPath = avatarsByName[peer.name] ?: peer.avatarPath)
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, -1)
                    _isWifiP2pEnabled.value = (state == WifiP2pManager.WIFI_P2P_STATE_ENABLED)
                }
                WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION -> {
                    wifiP2pManager?.requestPeers(channel) { peerList ->
                        val devices = peerList.deviceList.map { device ->
                            WifiDirectDevice(
                                name = device.deviceName?.ifBlank { "Nearby Device" } ?: "Nearby Device",
                                address = device.deviceAddress,
                                status = when (device.status) {
                                    WifiP2pDevice.CONNECTED  -> "Connected"
                                    WifiP2pDevice.INVITED    -> "Invited"
                                    WifiP2pDevice.FAILED     -> "Failed"
                                    WifiP2pDevice.AVAILABLE  -> "Available"
                                    else                     -> "Unavailable"
                                },
                                avatarPath = peerAvatarMap[device.deviceName],
                            )
                        }
                        _discoveredPeers.value = devices
                    }
                }
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    @Suppress("DEPRECATION")
                    val networkInfo = intent.getParcelableExtra<NetworkInfo>(WifiP2pManager.EXTRA_NETWORK_INFO)
                    if (networkInfo?.isConnected == true) {
                        wifiP2pManager?.requestConnectionInfo(channel) { info ->
                            _connectionInfo.value = info
                            Timber.i("Wi-Fi Direct Connected: isGroupOwner=%b host=%s",
                                info.isGroupOwner, info.groupOwnerAddress?.hostAddress)
                            if (info.groupFormed && info.isGroupOwner) {
                                appendLog("Connected as Group Owner — server ready")
                                startServer()
                            } else if (info.groupFormed) {
                                appendLog("Connected as client — group owner: ${info.groupOwnerAddress?.hostAddress}")
                            }
                        }
                    } else {
                        _connectionInfo.value = null
                        appendLog("Wi-Fi Direct disconnected")
                    }
                }
            }
        }
    }

    private fun registerReceiver() {
        if (isReceiverRegistered) return
        val intentFilter = IntentFilter().apply {
            addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)
            addAction(WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION)
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            intentFilter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        isReceiverRegistered = true
    }

    @SuppressLint("MissingPermission")
    fun startReceiveMode() {
        _isReceiveModeActive.value = true
        appendLog("Receive Mode started — creating P2P group…")
        startServer()

        wifiP2pManager?.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                appendLog("P2P group created — ready to receive")
                Timber.i("Wi-Fi Direct Group created — ready to receive")
            }
            override fun onFailure(reason: Int) {
                appendLog("Group creation failed ($reason) — falling back to discovery")
                Timber.w("Wi-Fi Direct createGroup failed: %d", reason)
                startDiscovery()
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun stopReceiveMode() {
        _isReceiveModeActive.value = false
        wifiP2pManager?.removeGroup(channel, null)
        serverJob?.cancel()
        try { serverSocket?.close() } catch (_: Exception) {}
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery(onSuccess: () -> Unit = {}, onFailure: (Int) -> Unit = {}) {
        wifiP2pManager?.discoverPeers(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                appendLog("Scanning for nearby devices…")
                Timber.i("Wi-Fi Direct discovery initiated")
                onSuccess()
            }
            override fun onFailure(reason: Int) {
                appendLog("Discovery failed ($reason). Make sure Wi-Fi is on.")
                Timber.w("Wi-Fi Direct discovery failed: %d", reason)
                onFailure(reason)
            }
        })
    }

    @SuppressLint("MissingPermission")
    fun connectToDevice(
        deviceAddress: String,
        onSuccess: () -> Unit = {},
        onFailure: (Int) -> Unit = {},
    ) {
        val config = WifiP2pConfig().apply {
            this.deviceAddress = deviceAddress
            wps.setup = WpsInfo.PBC
            groupOwnerIntent = 0
        }
        appendLog("Connecting to $deviceAddress…")
        wifiP2pManager?.connect(channel, config, object : WifiP2pManager.ActionListener {
            override fun onSuccess() {
                appendLog("Connection request sent to $deviceAddress")
                Timber.i("Wi-Fi Direct connect request sent to %s", deviceAddress)
                onSuccess()
            }
            override fun onFailure(reason: Int) {
                appendLog("Connection failed ($reason)")
                Timber.w("Wi-Fi Direct connect failed: %d", reason)
                onFailure(reason)
            }
        })
    }

    /**
     * Sends a file over a local TCP socket.
     * Waits for groupFormed if needed, then streams the file with live progress and detailed logs.
     */
    fun sendFile(
        fileUri: Uri,
        fileName: String,
        fileSize: Long,
        port: Int = 8988,
    ) {
        scope.launch {
            _transferProgress.update { it.copy(isTransferring = true, fileName = fileName, totalBytes = fileSize, bytesTransferred = 0L, error = null, logLines = emptyList(), isComplete = false) }
            appendLog("Preparing to send: $fileName (${formatBytes(fileSize)})")

            // Wait for P2P group to form and connection info to arrive (up to 20 seconds)
            var waitMs = 0
            while (_connectionInfo.value?.groupFormed != true && waitMs < 20_000) {
                delay(500)
                waitMs += 500
            }

            val info = _connectionInfo.value
            if (info?.groupFormed != true) {
                appendLog("❌ Error: No Wi-Fi Direct group formed — is receiver in Receive Mode?")
                _transferProgress.update { it.copy(isTransferring = false, error = "No P2P group formed. Make sure receiver has Receive Mode ON.") }
                return@launch
            }

            val targetHost = info.groupOwnerAddress?.hostAddress ?: "192.168.49.1"
            appendLog("Connecting to receiver at $targetHost:$port")

            var socket: Socket? = null
            var attempts = 0
            while (attempts < 12 && socket == null) {
                try {
                    val s = Socket()
                    s.bind(null)
                    s.connect(InetSocketAddress(targetHost, port), 4_000)
                    socket = s
                    appendLog("✅ Socket connected to $targetHost:$port")
                } catch (e: Exception) {
                    attempts++
                    appendLog("Retrying connection… ($attempts/12)")
                    delay(800)
                }
            }

            if (socket == null) {
                appendLog("❌ Failed to reach receiver. Try moving closer and retry.")
                _transferProgress.update { it.copy(isTransferring = false, error = "Could not connect to receiver at $targetHost:$port") }
                return@launch
            }

            try {
                val dataOut = DataOutputStream(socket.getOutputStream())
                dataOut.writeUTF(fileName)
                dataOut.writeLong(fileSize)

                val inputStream = context.contentResolver.openInputStream(fileUri)
                    ?: throw IOException("Cannot open file URI")
                val buffer = ByteArray(65_536)
                var bytesRead: Int
                var totalSent = 0L
                var lastLogAt = 0L

                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    dataOut.write(buffer, 0, bytesRead)
                    totalSent += bytesRead
                    _transferProgress.update { it.copy(bytesTransferred = totalSent) }

                    // Log every 500KB
                    if (totalSent - lastLogAt >= 524_288) {
                        val pct = (totalSent * 100 / fileSize).toInt()
                        appendLog("Sending… $pct% (${formatBytes(totalSent)} / ${formatBytes(fileSize)})")
                        lastLogAt = totalSent
                    }
                }

                dataOut.flush()
                inputStream.close()

                appendLog("✅ Transfer complete! ${formatBytes(fileSize)} sent.")
                _transferProgress.update { it.copy(isTransferring = false, isComplete = true, bytesTransferred = fileSize) }
                Timber.i("Wi-Fi Direct file send completed: %s (%d bytes)", fileName, fileSize)
            } catch (e: Exception) {
                Timber.e(e, "Wi-Fi Direct sendFile error")
                appendLog("❌ Transfer error: ${e.message}")
                _transferProgress.update { it.copy(isTransferring = false, error = e.message ?: "Transfer failed") }
            } finally {
                try { socket?.close() } catch (_: Exception) {}
            }
        }
    }

    fun startServer(port: Int = 8988) {
        if (serverJob?.isActive == true) return
        serverJob = scope.launch {
            try {
                serverSocket = ServerSocket(port)
                appendLog("Server listening on port $port")
                Timber.i("Wi-Fi Direct Server started on port %d", port)

                while (isActive) {
                    val client = serverSocket?.accept() ?: break
                    launch { handleIncomingFile(client) }
                }
            } catch (e: Exception) {
                Timber.w(e, "Wi-Fi Direct server stopped")
            } finally {
                try { serverSocket?.close() } catch (_: Exception) {}
                serverSocket = null
            }
        }
    }

    private fun handleIncomingFile(socket: Socket) {
        try {
            val dataIn = DataInputStream(socket.getInputStream())
            val fileName = dataIn.readUTF()
            val fileSize = dataIn.readLong()

            appendLog("📥 Incoming: $fileName (${formatBytes(fileSize)})")
            _transferProgress.update { it.copy(isReceiving = true, fileName = fileName, totalBytes = fileSize, bytesTransferred = 0L, isComplete = false) }

            val downloadsDir = File(context.getExternalFilesDir(null), "AirDrop")
            if (!downloadsDir.exists()) downloadsDir.mkdirs()
            val targetFile = File(downloadsDir, fileName)

            val fileOut = FileOutputStream(targetFile)
            val buffer = ByteArray(65_536)
            var bytesRead: Int
            var totalReceived = 0L
            var lastLogAt = 0L

            while (totalReceived < fileSize) {
                val toRead = minOf(buffer.size.toLong(), fileSize - totalReceived).toInt()
                bytesRead = dataIn.read(buffer, 0, toRead)
                if (bytesRead == -1) break
                fileOut.write(buffer, 0, bytesRead)
                totalReceived += bytesRead
                _transferProgress.update { it.copy(bytesTransferred = totalReceived) }

                if (totalReceived - lastLogAt >= 524_288) {
                    val pct = (totalReceived * 100 / fileSize).toInt()
                    appendLog("Receiving… $pct% (${formatBytes(totalReceived)} / ${formatBytes(fileSize)})")
                    lastLogAt = totalReceived
                }
            }

            fileOut.flush()
            fileOut.close()

            appendLog("✅ File received: $fileName saved to Downloads/AirDrop")
            _transferProgress.update {
                it.copy(isReceiving = false, isComplete = true, bytesTransferred = fileSize, receivedFilePath = targetFile.absolutePath)
            }
            Timber.i("Received file via Wi-Fi Direct: %s", targetFile.absolutePath)
        } catch (e: Exception) {
            Timber.e(e, "Error receiving file over Wi-Fi Direct")
            appendLog("❌ Receive error: ${e.message}")
            _transferProgress.update { it.copy(isReceiving = false, error = e.message) }
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun appendLog(line: String) {
        _transferProgress.update { it.copy(logLines = (it.logLines + line).takeLast(20)) }
    }

    fun resetTransferState() {
        _transferProgress.value = TransferProgress()
    }

    private fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        val idx = (Math.log10(bytes.toDouble()) / Math.log10(1024.0)).toInt().coerceIn(0, 3)
        return "%.1f %s".format(bytes / Math.pow(1024.0, idx.toDouble()), units[idx])
    }
}
