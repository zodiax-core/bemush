package com.campusmesh.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.campusmesh.ble.CampusMeshBle
import com.campusmesh.ble.NearbyPeer
import com.campusmesh.ble.PeerRegistry
import com.campusmesh.crypto.DecryptedMessage
import com.campusmesh.crypto.NodeKeyManager
import com.campusmesh.data.MessageRepository
import com.campusmesh.data.PeerRepository
import com.campusmesh.data.RelayRepository
import com.campusmesh.db.RelayPacketEntity
import com.campusmesh.identity.LocalNodeIdStore
import com.campusmesh.mesh.MeshMetricsTracker
import com.campusmesh.notification.MeshNotificationManager
import com.campusmesh.packet.MeshPacket
import com.campusmesh.packet.PacketCache
import com.campusmesh.packet.PacketProtocol
import com.campusmesh.profile.ProfileManager
import com.campusmesh.util.ImageUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DirectTransportController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageRepository: MessageRepository,
    private val relayRepository: RelayRepository,
    private val localNodeIdStore: LocalNodeIdStore,
    private val packetCache: PacketCache,
    private val nodeKeyManager: NodeKeyManager,
    private val peerRepository: PeerRepository,
    private val peerRegistry: PeerRegistry,
    private val meshMetricsTracker: MeshMetricsTracker,
    private val profileManager: ProfileManager,
    private val notificationManager: MeshNotificationManager,
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter? = bluetoothManager?.adapter

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Dedicated mutex for packet flushing
    private val flushMutex = Mutex()
    private val connectMutex = Mutex()

    // Per-device GATT operation mutexes to avoid concurrent command collisions
    private val gattMutexMap = ConcurrentHashMap<String, Mutex>()
    private fun getGattMutex(address: String): Mutex = gattMutexMap.computeIfAbsent(address) { Mutex() }

    // Track negotiated MTU per device address
    private val deviceMtu = ConcurrentHashMap<String, Int>()

    private var gattServer: BluetoothGattServer? = null

    // Active GATT Client connections keyed by Device Address
    private val activeGattClients = ConcurrentHashMap<String, BluetoothGatt>()
    // Connected Central devices to our GATT Server keyed by Device Address
    private val connectedServerDevices = ConcurrentHashMap<String, BluetoothDevice>()

    // Bidirectional mappings: Address <-> NodeId
    private val addressToNodeId = ConcurrentHashMap<String, String>()
    private val nodeIdToAddress = ConcurrentHashMap<String, String>()

    // Loop prevention: tracks the device address from which a packet was received
    private val packetLastHop = ConcurrentHashMap<String, String>()

    // Chunked identity buffer: bufferKey -> (chunkIndex -> chunkData)
    private val identityChunkBuffers = ConcurrentHashMap<String, ConcurrentHashMap<Int, String>>()

    // Chunked message packet buffer: "$address:$packetId" -> (chunkIndex -> chunkData)
    private val messagePacketBuffers = ConcurrentHashMap<String, ConcurrentHashMap<Int, String>>()
    private val messagePacketBufferTimestamps = ConcurrentHashMap<String, Long>()

    // Deduplication set for identity exchange
    private val exchangedIdentityPeers = ConcurrentHashMap.newKeySet<String>()

    // Reconnect / Watchdog jobs per device address
    private val reconnectJobs = ConcurrentHashMap<String, Job>()
    private val watchdogJobs = ConcurrentHashMap<String, Job>()

    interface CallPacketListener {
        fun onCallPacketReceived(senderNodeId: String, packet: com.campusmesh.call.CallPacket)
        fun onDirectVoiceFrameReceived(senderNodeId: String, seq: Byte, audioData: ByteArray) {}
        fun onPeerDisconnected(nodeId: String) {}
    }

    @Volatile
    private var callPacketListener: CallPacketListener? = null

    fun setCallPacketListener(listener: CallPacketListener?) {
        this.callPacketListener = listener
    }

    // Pending write & descriptor deferred completions
    private val writeDeferredMap = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val descriptorDeferredMap = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()
    private val notificationDeferredMap = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    // Outbox transmission tracking to avoid retry spam while awaiting ACKs
    private val packetLastSentTime = ConcurrentHashMap<String, Long>()
    // Tracks peripherals whose GATT services have been fully discovered
    private val servicesDiscoveredSet = ConcurrentHashMap.newKeySet<String>()

    /** Tracks the peer currently opened in ChatScreen for read receipt handling. */
    @Volatile var activeChatPeerId: String? = null

    private val _snapshot = MutableStateFlow(DirectTransportSnapshot())
    val snapshot: StateFlow<DirectTransportSnapshot> = _snapshot.asStateFlow()

    init {
        scope.launch {
            messageRepository.allMessages.collect { messages ->
                _snapshot.update { it.copy(persistedMessages = messages) }
            }
        }
        scope.launch { updateRelayPacketsSnapshot() }

        // When local profile changes, clear exchanged cache and push identity to all connected peers
        scope.launch {
            profileManager.localProfile.collect {
                exchangedIdentityPeers.clear()
                pushUpdatedIdentityToConnectedPeers()
            }
        }

        // Automatic Mesh Peer Connector:
        // Continuously connects to discovered nearby peers in the mesh (in foreground & background)
        scope.launch {
            peerRegistry.peers.collect { nearbyPeers ->
                maintainMeshConnections(nearbyPeers)
            }
        }

        // Active periodic packet retry loop: always runs every 1 second.
        // The actual GATT write only proceeds if connections are open, but the
        // loop itself must run unconditionally so new connections drain within 1 s.
        scope.launch {
            while (isActive) {
                delay(1_000)
                try {
                    flushPendingPackets()
                    cleanStaleChunkBuffers()
                } catch (_: Exception) {}
            }
        }

        // Periodic self-heal loop: every 10 seconds, attempt to connect to any
        // visible BLE peer that is not already connected. This covers the case
        // where the peerRegistry.peers flow didn't re-emit (peer list unchanged)
        // but a connection was silently dropped.
        scope.launch {
            while (isActive) {
                delay(10_000)
                try {
                    val nearbyPeers = peerRegistry.peers.value
                    for (peer in nearbyPeers) {
                        val addr = peer.deviceAddress
                        val peerId = peer.nodeId.toString()
                        if (!isPeerDirectlyConnected(peerId) &&
                            !activeGattClients.containsKey(addr) &&
                            !connectedServerDevices.containsKey(addr)
                        ) {
                            Timber.d("Self-heal: reconnecting to %s (%s)", peer.shortLabel, addr)
                            connectToPeer(addr, peerId, peer.shortLabel)
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // Bluetooth Adapter State Change listener: handles instant cleanup on OFF and clean recreation on ON
        val btFilter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        val btReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    Timber.i("DirectTransportController: BT state changed to %d", state)
                    when (state) {
                        BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                            onBluetoothDisabled()
                        }
                        BluetoothAdapter.STATE_ON -> {
                            onBluetoothRestarted()
                        }
                    }
                }
            }
        }
        try {
            context.registerReceiver(btReceiver, btFilter)
        } catch (_: Exception) {}
    }

    private fun getEffectiveChunkSize(address: String): Int {
        val mtu = deviceMtu[address] ?: 247
        // Safe chunk size keeping total chunk string ("PKT:uuid:i:total:slice") strictly inside ATT MTU (MTU - 3).
        // Protocol header is ~48 bytes. (mtu - 63) guarantees at least 60 bytes of headroom below (mtu - 3).
        return maxOf(20, minOf(mtu - 63, 440))
    }

    private fun cleanStaleChunkBuffers() {
        val now = System.currentTimeMillis()
        val it = messagePacketBufferTimestamps.entries.iterator()
        while (it.hasNext()) {
            val entry = it.next()
            if (now - entry.value > 60_000L) {
                messagePacketBuffers.remove(entry.key)
                it.remove()
            }
        }
    }

    /**
     * Resolves the connectable peripheral address for a given nodeId.
     * Prefers the live advertised address from PeerRegistry, falling back to cached address.
     */
    fun resolveConnectableAddress(nodeId: String): String? {
        return peerRegistry.peers.value.find { it.nodeId.toString() == nodeId }?.deviceAddress
            ?: nodeIdToAddress[nodeId]
    }

    /**
     * Automatic mesh connection maintenance:
     * Connects to discovered peers without dual-central connection collisions (GATT 133).
     * Deterministic tie-breaking: higher nodeId initiates client connection; lower accepts as server.
     */
    private suspend fun maintainMeshConnections(nearbyPeers: List<NearbyPeer>) {
        val localNodeIdStr = localNodeIdStore.nodeId.toString()
        for (peer in nearbyPeers) {
            val addr = peer.deviceAddress
            val peerId = peer.nodeId.toString()

            addressToNodeId[addr] = peerId
            nodeIdToAddress[peerId] = addr

            val isClient = activeGattClients.containsKey(addr)
            val isServer = connectedServerDevices.containsKey(addr)
            val isDirect = isPeerDirectlyConnected(peerId)
            val isWatchdogActive = watchdogJobs.containsKey(addr)

            if (isClient || isServer || isDirect) {
                // Already connected in the mesh (bidirectional link established)
                continue
            }

            if (!isWatchdogActive) {
                val isInitiator = localNodeIdStr > peerId
                if (isInitiator) {
                    Timber.d("Mesh Auto-Connect (initiator): connecting as client to %s (%s)", peer.shortLabel, addr)
                    connectToPeer(addr, peerId, peer.shortLabel)
                } else {
                    // Lower nodeId allows higher nodeId 1.5s to initiate, avoiding simultaneous central collisions
                    scope.launch {
                        delay(1500)
                        val stillUnconnected = !isPeerDirectlyConnected(peerId) &&
                                !activeGattClients.containsKey(addr) &&
                                !connectedServerDevices.containsKey(addr)
                        if (stillUnconnected && !watchdogJobs.containsKey(addr)) {
                            Timber.d("Mesh Auto-Connect (fallback): connecting as client to %s (%s)", peer.shortLabel, addr)
                            connectToPeer(addr, peerId, peer.shortLabel)
                        }
                    }
                }
            }
        }
    }

    private fun pushUpdatedIdentityToConnectedPeers() {
        scope.launch {
            val identityJson = buildIdentityPayload()

            // Push to all connected GATT clients
            for ((addr, gatt) in activeGattClients) {
                try {
                    val service = gatt.getService(CampusMeshBle.TRANSPORT_SERVICE_UUID)
                    val identityChar = service?.getCharacteristic(CampusMeshBle.PUBLIC_KEY_CHARACTERISTIC_UUID)
                    if (identityChar != null) {
                        sendChunkedIdentity(gatt, identityChar, identityJson)
                    }
                } catch (e: Exception) {
                    Timber.w(e, "pushUpdatedIdentity to client %s failed", addr)
                }
            }

            // Push to all connected GATT server devices
            val server = gattServer
            if (server != null) {
                val service = server.getService(CampusMeshBle.TRANSPORT_SERVICE_UUID)
                val identityChar = service?.getCharacteristic(CampusMeshBle.PUBLIC_KEY_CHARACTERISTIC_UUID)
                if (identityChar != null) {
                    for ((addr, device) in connectedServerDevices) {
                        try {
                            notifyChunkedIdentity(device, identityChar, identityJson)
                        } catch (e: Exception) {
                            Timber.w(e, "pushUpdatedIdentity to server device %s failed", addr)
                        }
                    }
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    // GATT Server (Accepts incoming connections from peers)
    // ──────────────────────────────────────────────────────────

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            val address = device?.address ?: return
            Timber.i("GATT Server state: device=%s status=%d newState=%d", address, status, newState)

            mainHandler.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectedServerDevices[address] = device
                    addLog("Peer connected to our server: $address")
                    syncSnapshot()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    val disconnectedNodeId = addressToNodeId[address] ?: nodeIdToAddress.entries.find { it.value == address }?.key
                    connectedServerDevices.remove(address)
                    deviceMtu.remove(address)
                    exchangedIdentityPeers.remove(address)
                    gattMutexMap.remove(address)
                    notificationDeferredMap.remove(address)?.complete(false)
                    addLog("Peer disconnected from our server: $address")
                    syncSnapshot()
                    if (disconnectedNodeId != null) {
                        callPacketListener?.onPeerDisconnected(disconnectedNodeId)
                    }
                }
            }
        }

        override fun onMtuChanged(device: BluetoothDevice?, mtu: Int) {
            super.onMtuChanged(device, mtu)
            val address = device?.address ?: return
            deviceMtu[address] = mtu
            Timber.i("GATT Server MTU for %s updated to %d", address, mtu)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            descriptor: BluetoothGattDescriptor?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            super.onDescriptorWriteRequest(device, requestId, descriptor, preparedWrite, responseNeeded, offset, value)
            val address = device?.address ?: "unknown"
            Timber.d("GATT Server: onDescriptorWriteRequest from %s for %s", address, descriptor?.uuid)
            descriptor?.value = value
            if (responseNeeded) {
                try {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, value)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to respond to descriptor write")
                }
            }

            // Once client enables CCCD notifications on server characteristics, notify identity & flush packets
            val isCccd = descriptor?.uuid == UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
            if (isCccd && device != null) {
                scope.launch {
                    delay(80)
                    sendIdentityBackToPeer(address)
                    flushPendingPackets()
                }
            }
        }

        override fun onCharacteristicReadRequest(
            device: BluetoothDevice?,
            requestId: Int,
            offset: Int,
            characteristic: BluetoothGattCharacteristic?
        ) {
            super.onCharacteristicReadRequest(device, requestId, offset, characteristic)
            val address = device?.address ?: "unknown"
            if (characteristic?.uuid == CampusMeshBle.PUBLIC_KEY_CHARACTERISTIC_UUID) {
                val payload = buildIdentityPayload().toByteArray(StandardCharsets.UTF_8)
                val slice = if (offset < payload.size) payload.copyOfRange(offset, payload.size) else ByteArray(0)
                try {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
                } catch (_: SecurityException) {}
            } else {
                try {
                    gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, offset, null)
                } catch (_: SecurityException) {}
            }
        }

        override fun onNotificationSent(device: BluetoothDevice?, status: Int) {
            super.onNotificationSent(device, status)
            val address = device?.address ?: "unknown"
            notificationDeferredMap.remove(address)?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice?,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic?,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray?,
        ) {
            super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value)
            val address = device?.address ?: "unknown"

            when (characteristic?.uuid) {
                CampusMeshBle.PUBLIC_KEY_CHARACTERISTIC_UUID -> {
                    if (value != null) {
                        val payload = String(value, StandardCharsets.UTF_8)
                        scope.launch { handleIncomingIdentity(payload, address) }
                    }
                    respondToWrite(device, requestId, responseNeeded, value)
                }

                CampusMeshBle.MESSAGE_CHARACTERISTIC_UUID -> {
                    if (value != null) {
                        handleIncomingPacketBytes(value, address)
                    }
                    respondToWrite(device, requestId, responseNeeded, value)
                }

                else -> {
                    if (responseNeeded) {
                        try {
                            gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_FAILURE, 0, null)
                        } catch (_: SecurityException) {}
                    }
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun respondToWrite(
        device: BluetoothDevice?,
        requestId: Int,
        responseNeeded: Boolean,
        value: ByteArray?,
    ) {
        if (responseNeeded) {
            try {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
            } catch (e: SecurityException) {
                Timber.e(e, "Failed to send GATT response")
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    // GATT Client (Connects outward to discovered peers)
    // ──────────────────────────────────────────────────────────

    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            val address = gatt?.device?.address ?: "unknown"
            Timber.i("GATT Client state: address=%s status=%d newState=%d", address, status, newState)

            mainHandler.post {
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    if (gatt != null) {
                        activeGattClients[address] = gatt
                    }
                    addLog("Connected as client to: $address")
                    cancelReconnect(address)
                    cancelWatchdog(address)
                    syncSnapshot()

                    // Request HIGH connection priority immediately for lowest latency BLE intervals
                    try {
                        gatt?.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                    } catch (_: Exception) {}

                    val mtuRequested = try {
                        gatt?.requestMtu(512) ?: false
                    } catch (e: SecurityException) {
                        false
                    }

                    // Safety fallback watchdog: if requestMtu fails or onMtuChanged is never dispatched by OEM,
                    // initiate discoverServices so the connection is never stalled
                    scope.launch {
                        delay(if (!mtuRequested) 50 else 350)
                        if (activeGattClients.containsKey(address) && !servicesDiscoveredSet.contains(address)) {
                            try {
                                @Suppress("MissingPermission")
                                gatt?.discoverServices()
                            } catch (_: Exception) {}
                        }
                    }
                } else {
                    activeGattClients.remove(address)
                    val disconnectedNodeId = addressToNodeId[address] ?: nodeIdToAddress.entries.find { it.value == address }?.key
                    deviceMtu.remove(address)
                    gattMutexMap.remove(address)
                    servicesDiscoveredSet.remove(address)
                    exchangedIdentityPeers.remove(address)
                    addLog("Client disconnected from: $address (status=$status)")
                    writeDeferredMap.remove(address)?.complete(false)
                    descriptorDeferredMap.remove(address)?.complete(false)
                    closeClientGatt(gatt)
                    syncSnapshot()
                    if (disconnectedNodeId != null) {
                        callPacketListener?.onPeerDisconnected(disconnectedNodeId)
                    }

                    if (_snapshot.value.peerAddress == address) {
                        scheduleReconnect(address)
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            val address = gatt?.device?.address ?: "unknown"
            if (status == BluetoothGatt.GATT_SUCCESS) {
                deviceMtu[address] = mtu
                mainHandler.post { addLog("MTU for $address: $mtu (High Speed)") }
            }
            if (!servicesDiscoveredSet.contains(address)) {
                scope.launch {
                    try {
                        @Suppress("MissingPermission")
                        gatt?.discoverServices()
                    } catch (e: SecurityException) {
                        Timber.e(e, "discoverServices failed")
                    }
                }
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
            super.onDescriptorWrite(gatt, descriptor, status)
            val address = gatt?.device?.address ?: "unknown"
            descriptorDeferredMap.remove(address)?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (status != BluetoothGatt.GATT_SUCCESS || gatt == null) return
            val address = gatt.device?.address ?: "unknown"
            servicesDiscoveredSet.add(address)
            addLog("Services discovered on: $address")

            scope.launch {
                try {
                    val service = gatt.getService(CampusMeshBle.TRANSPORT_SERVICE_UUID)
                    val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

                    // 1. Enable notifications on MESSAGE_CHARACTERISTIC_UUID
                    val msgChar = service?.getCharacteristic(CampusMeshBle.MESSAGE_CHARACTERISTIC_UUID)
                    if (msgChar != null) {
                        gatt.setCharacteristicNotification(msgChar, true)
                        val descriptor = msgChar.getDescriptor(cccdUuid)
                        if (descriptor != null) {
                            writeDescriptorLocked(gatt, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        }
                    }

                    // 2. Enable notifications on PUBLIC_KEY_CHARACTERISTIC_UUID (Enables two-way identity reception)
                    val identityChar = service?.getCharacteristic(CampusMeshBle.PUBLIC_KEY_CHARACTERISTIC_UUID)
                    if (identityChar != null) {
                        gatt.setCharacteristicNotification(identityChar, true)
                        val descriptor = identityChar.getDescriptor(cccdUuid)
                        if (descriptor != null) {
                            writeDescriptorLocked(gatt, descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                        }

                        // 3. Immediately transmit our identity
                        val identityJson = buildIdentityPayload()
                        sendChunkedIdentity(gatt, identityChar, identityJson)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error during post-discovery setup for $address")
                }
                flushPendingPackets()
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            val address = gatt?.device?.address ?: "unknown"
            val success = (status == BluetoothGatt.GATT_SUCCESS)
            if (!success) {
                Timber.w("Write failed to %s (%d)", address, status)
            }
            writeDeferredMap.remove(address)?.complete(success)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            super.onCharacteristicChanged(gatt, characteristic, value)
            val address = gatt.device?.address ?: "unknown"
            handleIncomingCharacteristicData(characteristic.uuid, value, address)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            super.onCharacteristicChanged(gatt, characteristic)
            // On Android 13+, the overload above receives the call; only run legacy on < 13 to prevent double-processing
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val value = characteristic?.value
                val address = gatt?.device?.address ?: "unknown"
                val uuid = characteristic?.uuid
                if (value != null && uuid != null) {
                    handleIncomingCharacteristicData(uuid, value, address)
                }
            }
        }
    }

    private fun handleIncomingCharacteristicData(uuid: UUID, value: ByteArray, address: String) {
        if (uuid == CampusMeshBle.PUBLIC_KEY_CHARACTERISTIC_UUID) {
            val payload = String(value, StandardCharsets.UTF_8)
            scope.launch { handleIncomingIdentity(payload, address) }
        } else {
            handleIncomingPacketBytes(value, address)
        }
    }

    // ──────────────────────────────────────────────────────────
    // Identity Exchange (Lightweight, Instant, Zero-Overhead)
    // ──────────────────────────────────────────────────────────

    private fun buildIdentityPayload(): String {
        val localProf = profileManager.localProfile.value
        val avatarFile = localProf.avatarPath?.let { File(it) }
        val avatarHash = avatarFile?.takeIf { it.exists() }?.let { ImageUtils.computeFileHash(it) } ?: ""

        val obj = JSONObject()
        obj.put("publicKey", nodeKeyManager.publicKeyBase64)
        obj.put("displayName", localProf.displayName)
        obj.put("nodeId", localNodeIdStore.nodeId.toString())
        obj.put("avatarHash", avatarHash)
        // Note: Avatars are decoupled from essential BLE identity packets to keep payload tiny (<300 bytes)
        return obj.toString()
    }

    private suspend fun sendChunkedIdentity(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        payloadJson: String,
    ): Boolean {
        val address = gatt.device?.address ?: return false
        val chunkSize = getEffectiveChunkSize(address)
        val rawBytes = payloadJson.toByteArray(StandardCharsets.UTF_8)
        val maxUnchunkedBytes = maxOf(20, (deviceMtu[address] ?: 247) - 3)

        if (rawBytes.size <= maxUnchunkedBytes && rawBytes.size <= 240) {
            return writeCharacteristicLocked(gatt, characteristic, rawBytes)
        }

        val sessionId = UUID.randomUUID().toString().take(6)
        val totalChunks = (payloadJson.length + chunkSize - 1) / chunkSize
        Timber.i("Sending chunked identity (%d chars, %d chunks)", payloadJson.length, totalChunks)

        for (i in 0 until totalChunks) {
            val start = i * chunkSize
            val end = minOf(start + chunkSize, payloadJson.length)
            val slice = payloadJson.substring(start, end)
            val chunkStr = "ID:$sessionId:$i:$totalChunks:$slice"
            val chunkBytes = chunkStr.toByteArray(StandardCharsets.UTF_8)
            val ok = writeCharacteristicLocked(gatt, characteristic, chunkBytes)
            if (!ok) {
                Timber.w("Failed to send identity chunk %d/%d to %s", i + 1, totalChunks, address)
                return false
            }
            delay(5)
        }
        return true
    }

    private suspend fun notifyChunkedIdentity(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        payloadJson: String,
    ): Boolean {
        val address = device.address
        val chunkSize = getEffectiveChunkSize(address)
        val rawBytes = payloadJson.toByteArray(StandardCharsets.UTF_8)
        val maxUnchunkedBytes = maxOf(20, (deviceMtu[address] ?: 247) - 3)

        if (rawBytes.size <= maxUnchunkedBytes && rawBytes.size <= 240) {
            return notifyPacketLocked(device, characteristic, rawBytes)
        }

        val sessionId = UUID.randomUUID().toString().take(6)
        val totalChunks = (payloadJson.length + chunkSize - 1) / chunkSize
        for (i in 0 until totalChunks) {
            val start = i * chunkSize
            val end = minOf(start + chunkSize, payloadJson.length)
            val slice = payloadJson.substring(start, end)
            val chunkStr = "ID:$sessionId:$i:$totalChunks:$slice"
            val chunkBytes = chunkStr.toByteArray(StandardCharsets.UTF_8)
            val ok = notifyPacketLocked(device, characteristic, chunkBytes)
            if (!ok) return false
            delay(5)
        }
        return true
    }

    private suspend fun handleIncomingIdentity(payload: String, address: String) {
        if (payload.startsWith("ID:")) {
            val parts = payload.split(":", limit = 5)
            if (parts.size >= 5) {
                val sessionId = parts[1]
                val chunkIndex = parts[2].toIntOrNull() ?: 0
                val totalChunks = parts[3].toIntOrNull() ?: 1
                val chunkData = parts[4]

                val bufferKey = "$address:$sessionId"
                val buffer = identityChunkBuffers.computeIfAbsent(bufferKey) { ConcurrentHashMap() }
                buffer[chunkIndex] = chunkData

                if ((0 until totalChunks).all { buffer.containsKey(it) }) {
                    val assembled = (0 until totalChunks).joinToString("") { buffer[it] ?: "" }
                    identityChunkBuffers.remove(bufferKey)
                    handleIdentityPayload(assembled, address)
                }
            }
        } else if (payload.startsWith("CHUNK:")) {
            // Backward-compatible fallback for legacy 4-part CHUNK prefix
            val firstColon = payload.indexOf(':')
            val secondColon = if (firstColon != -1) payload.indexOf(':', firstColon + 1) else -1
            val thirdColon = if (secondColon != -1) payload.indexOf(':', secondColon + 1) else -1

            if (firstColon != -1 && secondColon != -1 && thirdColon != -1) {
                val chunkIndex = payload.substring(firstColon + 1, secondColon).toIntOrNull() ?: 0
                val totalChunks = payload.substring(secondColon + 1, thirdColon).toIntOrNull() ?: 1
                val chunkData = payload.substring(thirdColon + 1)

                val buffer = identityChunkBuffers.computeIfAbsent(address) { ConcurrentHashMap() }
                buffer[chunkIndex] = chunkData

                if ((0 until totalChunks).all { buffer.containsKey(it) }) {
                    val assembled = (0 until totalChunks).joinToString("") { buffer[it] ?: "" }
                    identityChunkBuffers.remove(address)
                    handleIdentityPayload(assembled, address)
                }
            }
        } else {
            handleIdentityPayload(payload, address)
        }
    }

    private suspend fun handleIdentityPayload(payload: String, deviceAddress: String) {
        // Fast-path: handle lightweight avatar control packets before full JSON parse
        if (payload.startsWith("{") && payload.contains("\"type\"")) {
            try {
                val ctrl = JSONObject(payload)
                when (ctrl.optString("type")) {
                    "AVATAR_REQUEST" -> {
                        Timber.d("Received AVATAR_REQUEST from %s", deviceAddress)
                        sendAvatarToPeer(deviceAddress)
                        return
                    }
                    "AVATAR_DATA" -> {
                        Timber.d("Received AVATAR_DATA from %s", deviceAddress)
                        val base64 = ctrl.optString("avatarBase64").ifBlank { null } ?: return
                        val hash = ctrl.optString("avatarHash").ifBlank { null }
                        val nodeId = ctrl.optString("nodeId").ifBlank { addressToNodeId[deviceAddress] } ?: return
                        val existingPeer = peerRepository.getPeer(nodeId)
                        val savedFile = ImageUtils.saveBase64Avatar(
                            context = context,
                            base64Data = base64,
                            peerNodeId = nodeId,
                            previousFilePath = existingPeer?.avatarPath
                        )
                        if (savedFile != null) {
                            peerRepository.upsertPeer(
                                nodeId = nodeId,
                                deviceAddress = deviceAddress,
                                rssiDbm = existingPeer?.rssiDbm ?: -50,
                                lastSeenEpochMs = System.currentTimeMillis(),
                                avatarPath = savedFile.absolutePath,
                                avatarHash = hash,
                            )
                            Timber.i("Saved avatar for peer %s -> %s", nodeId, savedFile.absolutePath)
                            mainHandler.post { syncSnapshot() }
                        }
                        return
                    }
                }
            } catch (_: Exception) {}
        }
        try {
            val obj = JSONObject(payload)
            val pubKey = obj.optString("publicKey").ifBlank { null }
            val displayName = obj.optString("displayName").ifBlank { null }
            val nodeId = obj.optString("nodeId").ifBlank { deviceAddress }
            val avatarHash = obj.optString("avatarHash").ifBlank { null }

            addressToNodeId[deviceAddress] = nodeId
            nodeIdToAddress[nodeId] = deviceAddress

            val existingPeer = peerRepository.getPeer(nodeId)

            peerRepository.upsertPeer(
                nodeId = nodeId,
                deviceAddress = deviceAddress,
                rssiDbm = -50,
                lastSeenEpochMs = System.currentTimeMillis(),
                publicKeyBase64 = pubKey,
                displayName = displayName,
                avatarPath = existingPeer?.avatarPath,
                avatarHash = avatarHash,
            )

            mainHandler.post {
                val effectiveLabel = existingPeer?.customName?.ifBlank { null }
                    ?: displayName ?: nodeId.take(8).uppercase()
                if (_snapshot.value.peerNodeId == nodeId || _snapshot.value.peerAddress == deviceAddress) {
                    _snapshot.update {
                        it.copy(
                            peerNodeId = nodeId,
                            peerLabel = effectiveLabel,
                        )
                    }
                }
                syncSnapshot()
            }

            // Two-Way Handshake: Send our own identity back if not yet exchanged
            if (exchangedIdentityPeers.add(nodeId)) {
                sendIdentityBackToPeer(deviceAddress)
            }

            // On-demand avatar fetch: request if missing or avatar hash changed
            val existingAvatarPath = existingPeer?.avatarPath
            val existingAvatarHash = existingPeer?.avatarHash
            val needsAvatar = !avatarHash.isNullOrBlank() &&
                (existingAvatarPath == null ||
                 !File(existingAvatarPath).exists() ||
                 existingAvatarHash != avatarHash)
            if (needsAvatar) {
                requestAvatarFromPeer(deviceAddress, nodeId, avatarHash!!)
            }

            if (activeChatPeerId == nodeId) {
                markConversationAsRead(nodeId)
            }

            flushPendingPackets()
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse identity payload")
        }
    }

    private fun sendIdentityBackToPeer(deviceAddress: String) {
        scope.launch {
            val identityJson = buildIdentityPayload()

            // If we have a client connection to this address, write identity
            val client = activeGattClients[deviceAddress]
            if (client != null) {
                val s = client.getService(CampusMeshBle.TRANSPORT_SERVICE_UUID)
                val idChar = s?.getCharacteristic(CampusMeshBle.PUBLIC_KEY_CHARACTERISTIC_UUID)
                if (idChar != null) {
                    sendChunkedIdentity(client, idChar, identityJson)
                }
            }

            // If device is connected to our server, notify identity
            val serverDevice = connectedServerDevices[deviceAddress]
            val server = gattServer
            if (serverDevice != null && server != null) {
                val s = server.getService(CampusMeshBle.TRANSPORT_SERVICE_UUID)
                val idChar = s?.getCharacteristic(CampusMeshBle.PUBLIC_KEY_CHARACTERISTIC_UUID)
                if (idChar != null) {
                    notifyChunkedIdentity(serverDevice, idChar, identityJson)
                }
            }
        }
    }

    /**
     * Sends an AVATAR_REQUEST control packet to [deviceAddress] asking for avatar [avatarHash].
     * The request is lightweight (tiny JSON) and sent directly via the identity characteristic.
     */
    private fun requestAvatarFromPeer(deviceAddress: String, nodeId: String, avatarHash: String) {
        scope.launch {
            val request = JSONObject().apply {
                put("type", "AVATAR_REQUEST")
                put("nodeId", localNodeIdStore.nodeId.toString())
                put("avatarHash", avatarHash)
            }.toString()

            val client = activeGattClients[deviceAddress]
            if (client != null) {
                val s = client.getService(CampusMeshBle.TRANSPORT_SERVICE_UUID)
                val idChar = s?.getCharacteristic(CampusMeshBle.PUBLIC_KEY_CHARACTERISTIC_UUID)
                if (idChar != null) {
                    sendChunkedIdentity(client, idChar, request)
                    return@launch
                }
            }
            val serverDevice = connectedServerDevices[deviceAddress]
            val server = gattServer
            if (serverDevice != null && server != null) {
                val s = server.getService(CampusMeshBle.TRANSPORT_SERVICE_UUID)
                val idChar = s?.getCharacteristic(CampusMeshBle.PUBLIC_KEY_CHARACTERISTIC_UUID)
                if (idChar != null) {
                    notifyChunkedIdentity(serverDevice, idChar, request)
                }
            }
        }
    }

    /**
     * Sends our avatar image data back to [deviceAddress] in response to an AVATAR_REQUEST.
     */
    private fun sendAvatarToPeer(deviceAddress: String) {
        scope.launch {
            val profile = profileManager.localProfile.value
            val avatarFile = profile.avatarPath?.let { File(it) }?.takeIf { it.exists() } ?: return@launch
            val base64 = ImageUtils.encodeFileToBase64(avatarFile) ?: return@launch
            val avatarHash = ImageUtils.computeFileHash(avatarFile)

            val response = JSONObject().apply {
                put("type", "AVATAR_DATA")
                put("nodeId", localNodeIdStore.nodeId.toString())
                put("avatarBase64", base64)
                put("avatarHash", avatarHash)
            }.toString()

            val client = activeGattClients[deviceAddress]
            if (client != null) {
                val s = client.getService(CampusMeshBle.TRANSPORT_SERVICE_UUID)
                val idChar = s?.getCharacteristic(CampusMeshBle.PUBLIC_KEY_CHARACTERISTIC_UUID)
                if (idChar != null) {
                    sendChunkedIdentity(client, idChar, response)
                    return@launch
                }
            }
            val serverDevice = connectedServerDevices[deviceAddress]
            val server = gattServer
            if (serverDevice != null && server != null) {
                val s = server.getService(CampusMeshBle.TRANSPORT_SERVICE_UUID)
                val idChar = s?.getCharacteristic(CampusMeshBle.PUBLIC_KEY_CHARACTERISTIC_UUID)
                if (idChar != null) {
                    notifyChunkedIdentity(serverDevice, idChar, response)
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    // Incoming Packet Handling & MTU-Safe Chunking
    // ──────────────────────────────────────────────────────────

    private fun handleIncomingPacketBytes(value: ByteArray, address: String) {
        // Fast path: VOX binary real-time audio frame
        if (value.size >= 4 && value[0] == 'V'.code.toByte() && value[1] == 'O'.code.toByte() && value[2] == 'X'.code.toByte()) {
            val seq = value[3]
            val audioPayload = if (value.size > 4) value.copyOfRange(4, value.size) else ByteArray(0)
            val senderNodeId = addressToNodeId[address] ?: address
            callPacketListener?.onDirectVoiceFrameReceived(senderNodeId, seq, audioPayload)
            return
        }

        val str = String(value, StandardCharsets.UTF_8)
        if (str.startsWith("PKT:")) {
            val parts = str.split(":", limit = 5)
            if (parts.size >= 5) {
                val pktId = parts[1]
                val idx = parts[2].toIntOrNull() ?: 0
                val total = parts[3].toIntOrNull() ?: 1
                val slice = parts[4]

                val bufferKey = "$address:$pktId"
                val buffer = messagePacketBuffers.computeIfAbsent(bufferKey) { ConcurrentHashMap() }
                buffer[idx] = slice
                messagePacketBufferTimestamps[bufferKey] = System.currentTimeMillis()

                if ((0 until total).all { buffer.containsKey(it) }) {
                    val fullJson = (0 until total).joinToString("") { buffer[it] ?: "" }
                    messagePacketBuffers.remove(bufferKey)
                    messagePacketBufferTimestamps.remove(bufferKey)
                    processIncomingPacketJson(fullJson, address, fullJson.length.toLong())
                }
            } else {
                // Fallback for legacy PKT:index:total:slice
                val firstColon = str.indexOf(':')
                val secondColon = if (firstColon != -1) str.indexOf(':', firstColon + 1) else -1
                val thirdColon = if (secondColon != -1) str.indexOf(':', secondColon + 1) else -1

                if (firstColon != -1 && secondColon != -1 && thirdColon != -1) {
                    val idx = str.substring(firstColon + 1, secondColon).toIntOrNull() ?: 0
                    val total = str.substring(secondColon + 1, thirdColon).toIntOrNull() ?: 1
                    val slice = str.substring(thirdColon + 1)

                    val buffer = messagePacketBuffers.computeIfAbsent(address) { ConcurrentHashMap() }
                    buffer[idx] = slice

                    if ((0 until total).all { buffer.containsKey(it) }) {
                        val fullJson = (0 until total).joinToString("") { buffer[it] ?: "" }
                        messagePacketBuffers.remove(address)
                        processIncomingPacketJson(fullJson, address, fullJson.length.toLong())
                    }
                }
            }
        } else {
            processIncomingPacketJson(str, address, value.size.toLong())
        }
    }

    private fun processIncomingPacketJson(payloadString: String, address: String, byteCount: Long) {
        mainHandler.post {
            _snapshot.update {
                it.copy(
                    bytesReceived = it.bytesReceived + byteCount,
                    lastReceivedMessage = payloadString.take(50),
                )
            }
        }

        scope.launch {
            val packet = PacketProtocol.deserialize(payloadString) ?: return@launch
            if (!PacketProtocol.isValid(packet)) return@launch

            // Track last hop address to avoid bouncing back
            packetLastHop[packet.packetId] = address

            val localNodeId = localNodeIdStore.nodeId.toString()

            if (packetCache.hasSeen(packet.packetId)) {
                meshMetricsTracker.recordDuplicateDiscarded()
                return@launch
            }
            packetCache.markSeen(packet.packetId)

            if (PacketProtocol.isExpired(packet)) {
                meshMetricsTracker.recordExpired()
                return@launch
            }

            // Route 1: Packet reached its destination (This node)
            if (packet.destinationId == localNodeId) {
                val isEncrypted = packet.payload.contains("\"encryptedAesKey\"")
                var decrypted: DecryptedMessage? = null
                var fallbackSenderKey: String? = null

                if (isEncrypted) {
                    try {
                        decrypted = nodeKeyManager.decrypt(packet.payload)
                    } catch (e: Exception) {
                        Timber.e(e, "Decryption error for packet %s from %s", packet.packetId, packet.sourceId)
                    }

                    // Extract sender's public key from JSON even if AES decrypt failed
                    if (decrypted == null) {
                        try {
                            val json = JSONObject(packet.payload)
                            val k = json.optString("senderPublicKey")
                            if (k.isNotBlank()) fallbackSenderKey = k
                        } catch (_: Exception) {}

                        // Request key sync back to sender
                        sendIdentityBackToPeer(address)
                    }
                }

                val senderPubKey = decrypted?.senderPublicKey ?: fallbackSenderKey
                if (!senderPubKey.isNullOrBlank()) {
                    peerRepository.upsertPeer(
                        nodeId = packet.sourceId,
                        deviceAddress = address,
                        rssiDbm = -50,
                        lastSeenEpochMs = System.currentTimeMillis(),
                        publicKeyBase64 = senderPubKey,
                    )
                }

                val content = when {
                    !isEncrypted -> packet.payload
                    decrypted != null -> decrypted.plaintext
                    else -> "[Encrypted Message — Syncing Keys]"
                }

                // Case A: Read Receipt
                if (content.startsWith("{") && content.contains("\"type\":\"READ_RECEIPT\"")) {
                    Timber.i("Received READ_RECEIPT from %s", packet.sourceId)
                    messageRepository.updateAllOutgoingStatusForPeer(packet.sourceId, "SEEN")
                    updateRelayPacketsSnapshot()
                    return@launch
                }

                // Case B: Delivery Acknowledgment
                if (content.startsWith("{") && content.contains("\"type\":\"DELIVERY_ACK\"")) {
                    try {
                        val json = JSONObject(content)
                        val ackMsgId = json.optString("messageId")
                        Timber.i("Received DELIVERY_ACK for message %s from %s", ackMsgId, packet.sourceId)
                        messageRepository.updateMessageStatus(ackMsgId, "DELIVERED")

                        // Remove matching message packet from relay store
                        val allRelay = relayRepository.getAllPackets()
                        for (r in allRelay) {
                            val p = PacketProtocol.deserialize(r.payloadJson)
                            if (p?.messageId == ackMsgId) {
                                relayRepository.removePacket(r.packetId)
                                packetLastSentTime.remove(r.packetId)
                            }
                        }
                        updateRelayPacketsSnapshot()
                    } catch (_: Exception) {}
                    return@launch
                }

                // Case C: Call Packet (Voice Calling Signaling or Audio)
                if (content.startsWith("{") && content.contains("\"type\":\"CALL_")) {
                    val callPacket = com.campusmesh.call.CallPacket.deserialize(content)
                    if (callPacket != null) {
                        callPacketListener?.onCallPacketReceived(packet.sourceId, callPacket)
                    }
                    return@launch
                }

                meshMetricsTracker.recordHopCount(packet.hopCount)
                meshMetricsTracker.recordDelivery(packet.timestamp, System.currentTimeMillis())

                val isCurrentChatOpen = (activeChatPeerId == packet.sourceId)
                val status = if (isCurrentChatOpen) "SEEN" else "DELIVERED"
                val isRead = isCurrentChatOpen

                messageRepository.saveMessage(
                    messageId = packet.messageId,
                    senderId = packet.sourceId,
                    recipientId = localNodeId,
                    content = content,
                    timestamp = packet.timestamp,
                    status = status,
                    isRead = isRead,
                )

                // Send Delivery Ack back to sender through the mesh
                sendDeliveryAckPacket(packet.sourceId, packet.messageId)

                if (isCurrentChatOpen) {
                    sendReadReceiptPacket(packet.sourceId)
                } else {
                    val peer = peerRepository.getPeer(packet.sourceId)
                    val senderName = peer?.customName?.ifBlank { null }
                        ?: peer?.displayName?.ifBlank { null }
                        ?: packet.sourceId.take(8).uppercase()
                    notificationManager.showIncomingMessageNotification(
                        senderNodeId = packet.sourceId,
                        senderName = senderName,
                        messageText = content,
                        avatarPath = peer?.avatarPath,
                    )
                }

                updateRelayPacketsSnapshot()
                flushPendingPackets()
            } else {
                // Route 2: Packet is for another node -> STORE AND FORWARD RELAY
                if (packet.ttl > 1) {
                    val updated = packet.copy(ttl = packet.ttl - 1, hopCount = packet.hopCount + 1)
                    val updatedJson = PacketProtocol.serialize(updated)

                    // Forward audio frames immediately through memory without writing to SQLite disk
                    if (packet.payload.contains("\"type\":\"CALL_AUDIO\"")) {
                        forwardAudioPacketMemory(updated.packetId, updatedJson, packetLastHop[packet.packetId])
                    } else {
                        relayRepository.storePacket(
                            packetId = packet.packetId,
                            destinationId = packet.destinationId,
                            payloadJson = updatedJson,
                            ttl = updated.ttl,
                            createdAt = packet.timestamp,
                            expiresAt = packet.timestamp + 86_400_000L,
                        )
                        meshMetricsTracker.recordForwarded()
                        updateRelayPacketsSnapshot()

                        // Forward immediately to all other connected peers
                        flushPendingPackets()
                    }
                }
            }
        }
    }

    private suspend fun sendDeliveryAckPacket(recipientNodeId: String, targetMessageId: String) {
        val localNodeId = localNodeIdStore.nodeId.toString()
        val now = System.currentTimeMillis()
        val controlPayload = JSONObject().apply {
            put("type", "DELIVERY_ACK")
            put("messageId", targetMessageId)
            put("timestamp", now)
        }.toString()

        val packet = MeshPacket(
            protocolVersion = 1,
            packetId = UUID.randomUUID().toString(),
            messageId = UUID.randomUUID().toString(),
            sourceId = localNodeId,
            destinationId = recipientNodeId,
            timestamp = now,
            ttl = 7,
            hopCount = 0,
            payload = controlPayload,
        )

        val payloadJson = PacketProtocol.serialize(packet)
        relayRepository.storePacket(
            packetId = packet.packetId,
            destinationId = recipientNodeId,
            payloadJson = payloadJson,
            ttl = 7,
            createdAt = now,
            expiresAt = now + 86_400_000L,
        )
        flushPendingPackets()
    }

    fun markConversationAsRead(peerNodeId: String) {
        scope.launch {
            messageRepository.markConversationAsRead(peerNodeId)
            sendReadReceiptPacket(peerNodeId)
        }
    }

    private suspend fun sendReadReceiptPacket(peerNodeId: String) {
        val localNodeId = localNodeIdStore.nodeId.toString()
        val now = System.currentTimeMillis()
        val controlPayload = JSONObject().apply {
            put("type", "READ_RECEIPT")
            put("timestamp", now)
        }.toString()

        val packet = MeshPacket(
            protocolVersion = 1,
            packetId = UUID.randomUUID().toString(),
            messageId = UUID.randomUUID().toString(),
            sourceId = localNodeId,
            destinationId = peerNodeId,
            timestamp = now,
            ttl = 7,
            hopCount = 0,
            payload = controlPayload,
        )

        val payloadJson = PacketProtocol.serialize(packet)
        relayRepository.storePacket(
            packetId = packet.packetId,
            destinationId = peerNodeId,
            payloadJson = payloadJson,
            ttl = 7,
            createdAt = now,
            expiresAt = now + 300_000L,
        )
        flushPendingPackets()
    }

    private suspend fun forwardAudioPacketMemory(packetId: String, packetJson: String, lastHop: String?) {
        for ((clientAddr, gatt) in activeGattClients) {
            if (clientAddr == lastHop) continue
            val s = gatt.getService(CampusMeshBle.TRANSPORT_SERVICE_UUID)
            val c = s?.getCharacteristic(CampusMeshBle.MESSAGE_CHARACTERISTIC_UUID)
            if (c != null) {
                sendPacketWithChunking(gatt, c, packetId, packetJson)
            }
        }
        val server = gattServer
        if (server != null) {
            val s = server.getService(CampusMeshBle.TRANSPORT_SERVICE_UUID)
            val c = s?.getCharacteristic(CampusMeshBle.MESSAGE_CHARACTERISTIC_UUID)
            if (c != null) {
                for ((devAddr, dev) in connectedServerDevices) {
                    if (devAddr == lastHop) continue
                    notifyPacketWithChunking(dev, c, packetId, packetJson)
                }
            }
        }
    }

    fun findActiveGattClientForNodeId(nodeId: String): BluetoothGatt? {
        val addr = resolveConnectableAddress(nodeId)
        if (addr != null && activeGattClients.containsKey(addr)) {
            return activeGattClients[addr]
        }
        for ((clientAddr, gatt) in activeGattClients) {
            if (addressToNodeId[clientAddr] == nodeId) {
                return gatt
            }
        }
        if (activeGattClients.size == 1) {
            return activeGattClients.values.firstOrNull()
        }
        return null
    }

    fun findConnectedServerDeviceForNodeId(nodeId: String): BluetoothDevice? {
        for ((addr, dev) in connectedServerDevices) {
            if (addressToNodeId[addr] == nodeId) {
                return dev
            }
        }
        val cachedAddr = nodeIdToAddress[nodeId]
        if (cachedAddr != null && connectedServerDevices.containsKey(cachedAddr)) {
            return connectedServerDevices[cachedAddr]
        }
        if (connectedServerDevices.size == 1) {
            return connectedServerDevices.values.firstOrNull()
        }
        return null
    }

    fun sendVoiceFrameDirect(destNodeId: String, seq: Byte, audioData: ByteArray) {
        if (audioData.isEmpty()) return

        // 4 bytes header ('V', 'O', 'X', seq) + 160 bytes audio payload = 164 bytes
        val frameBytes = ByteArray(4 + audioData.size)
        frameBytes[0] = 'V'.code.toByte()
        frameBytes[1] = 'O'.code.toByte()
        frameBytes[2] = 'X'.code.toByte()
        frameBytes[3] = seq
        System.arraycopy(audioData, 0, frameBytes, 4, audioData.size)

        scope.launch {
            // Attempt 1: If peer is peripheral (we are central client), fast write with NO_RESPONSE
            val client = findActiveGattClientForNodeId(destNodeId)
            val clientAddr = client?.device?.address
            val isClientReady = client != null && clientAddr != null && servicesDiscoveredSet.contains(clientAddr)
            if (isClientReady) {
                val s = client.getService(CampusMeshBle.TRANSPORT_SERVICE_UUID)
                val c = s?.getCharacteristic(CampusMeshBle.MESSAGE_CHARACTERISTIC_UUID)
                if (c != null) {
                    val clientOk = try {
                        @Suppress("DEPRECATION")
                        c.value = frameBytes
                        c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            client.writeCharacteristic(c, frameBytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothStatusCodes.SUCCESS
                        } else {
                            @Suppress("DEPRECATION")
                            client.writeCharacteristic(c)
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Error sending fast voice frame via GATT client")
                        false
                    }
                    if (clientOk) return@launch
                }
            }

            // Attempt 2: If peer is central (we are peripheral server), fast unconfirmed notification
            val server = gattServer
            if (server != null && connectedServerDevices.isNotEmpty()) {
                val dev = findConnectedServerDeviceForNodeId(destNodeId) ?: connectedServerDevices.values.firstOrNull()
                if (dev != null) {
                    val s = server.getService(CampusMeshBle.TRANSPORT_SERVICE_UUID)
                    val c = s?.getCharacteristic(CampusMeshBle.MESSAGE_CHARACTERISTIC_UUID)
                    if (c != null) {
                        try {
                            @Suppress("DEPRECATION")
                            c.value = frameBytes
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                server.notifyCharacteristicChanged(dev, c, false, frameBytes)
                            } else {
                                @Suppress("DEPRECATION")
                                server.notifyCharacteristicChanged(dev, c, false)
                            }
                            return@launch
                        } catch (e: Exception) {
                            Timber.w(e, "Error sending fast voice frame via GATT server notification")
                        }
                    }
                }
            }
        }
    }

    fun sendCallPacket(destNodeId: String, callPacket: com.campusmesh.call.CallPacket) {
        val localNodeId = localNodeIdStore.nodeId.toString()
        val now = System.currentTimeMillis()
        val payloadJson = com.campusmesh.call.CallPacket.serialize(callPacket)

        val packet = MeshPacket(
            protocolVersion = 1,
            packetId = java.util.UUID.randomUUID().toString(),
            messageId = callPacket.callId,
            sourceId = localNodeId,
            destinationId = destNodeId,
            timestamp = now,
            ttl = 5,
            hopCount = 0,
            payload = payloadJson,
        )
        val packetJson = PacketProtocol.serialize(packet)

        scope.launch {
            if (callPacket is com.campusmesh.call.CallPacket.AudioFrame) {
                sendVoiceFrameDirect(destNodeId, callPacket.seq.toByte(), callPacket.audioData)
            } else {
                // High-priority direct signaling: transmit immediately over active connection
                sendCallPacketDirectInternal(destNodeId, packet, packetJson)
            }
        }
    }

    private suspend fun sendCallPacketDirectInternal(destNodeId: String, packet: MeshPacket, packetJson: String): Boolean {
        var delivered = false

        // 1. Direct GATT client write
        val gatt = findActiveGattClientForNodeId(destNodeId)
        if (gatt != null) {
            val s = gatt.getService(CampusMeshBle.TRANSPORT_SERVICE_UUID)
            val c = s?.getCharacteristic(CampusMeshBle.MESSAGE_CHARACTERISTIC_UUID)
            if (c != null) {
                delivered = sendPacketWithChunking(gatt, c, packet.packetId, packetJson)
            }
        }

        // 2. Direct GATT server notification
        if (!delivered) {
            val serverDevice = findConnectedServerDeviceForNodeId(destNodeId)
            val server = gattServer
            if (serverDevice != null && server != null) {
                val s = server.getService(CampusMeshBle.TRANSPORT_SERVICE_UUID)
                val c = s?.getCharacteristic(CampusMeshBle.MESSAGE_CHARACTERISTIC_UUID)
                if (c != null) {
                    delivered = notifyPacketWithChunking(serverDevice, c, packet.packetId, packetJson)
                }
            }
        }

        // 3. Fallback: store in relay repository for mesh multi-hop if direct was not available
        if (!delivered) {
            Timber.w("Direct delivery of call signaling %s to %s failed, queuing in relay", packet.messageId, destNodeId)
            val now = System.currentTimeMillis()
            relayRepository.storePacket(
                packetId = packet.packetId,
                destinationId = destNodeId,
                payloadJson = packetJson,
                ttl = 5,
                createdAt = now,
                expiresAt = now + 60_000L,
            )
            flushPendingPackets()
        } else {
            Timber.i("Direct delivery of call signaling %s to %s succeeded", packet.messageId, destNodeId)
        }
        return delivered
    }

    // ──────────────────────────────────────────────────────────
    // Fast Packet Flushing & Multi-Peer Relay Engine
    // ──────────────────────────────────────────────────────────

    fun flushPendingPackets() {
        scope.launch {
            flushMutex.withLock { flushPendingPacketsLocked() }
        }
    }

    private suspend fun flushPendingPacketsLocked() {
        val hasClients = activeGattClients.isNotEmpty()
        val hasServerDevices = connectedServerDevices.isNotEmpty()

        if (!hasClients && !hasServerDevices) return

        try {
            val localNodeId = localNodeIdStore.nodeId.toString()
            val allPackets = relayRepository.getAllPackets()
            if (allPackets.isEmpty()) return

            var sentCount = 0

            for (entity in allPackets) {
                val packet = PacketProtocol.deserialize(entity.payloadJson) ?: continue
                if (packet.destinationId == localNodeId) continue

                // Outbox backoff: If recently transmitted, wait 2.5 s before retrying.
                // Applies to ALL packets (own messages and relayed ones) to prevent GATT congestion.
                val now = System.currentTimeMillis()
                val lastSent = packetLastSentTime[packet.packetId] ?: 0L
                if (now - lastSent < 2500L) {
                    continue
                }

                val lastHop = packetLastHop[packet.packetId]
                var delivered = false

                // 1. Direct Delivery: check active GATT clients first (link-layer acknowledged)
                val directClient = findActiveGattClientForNodeId(packet.destinationId)
                if (directClient != null) {
                    val s = directClient.getService(CampusMeshBle.TRANSPORT_SERVICE_UUID)
                    val c = s?.getCharacteristic(CampusMeshBle.MESSAGE_CHARACTERISTIC_UUID)
                    if (c != null) {
                        val ok = sendPacketWithChunking(directClient, c, packet.packetId, entity.payloadJson)
                        if (ok) delivered = true
                    }
                }

                // 2. Direct Delivery: check if peer is connected to our GATT Server
                if (!delivered) {
                    val directServerDevice = findConnectedServerDeviceForNodeId(packet.destinationId)
                    val server = gattServer
                    if (directServerDevice != null && server != null) {
                        val s = server.getService(CampusMeshBle.TRANSPORT_SERVICE_UUID)
                        val c = s?.getCharacteristic(CampusMeshBle.MESSAGE_CHARACTERISTIC_UUID)
                        if (c != null) {
                            val ok = notifyPacketWithChunking(directServerDevice, c, packet.packetId, entity.payloadJson)
                            if (ok) delivered = true
                        }
                    }
                }

                // 3. Multi-Hop Store & Forward: forward to other connected mesh peers
                if (!delivered) {
                    var forwardedToAny = false

                    for ((clientAddr, gatt) in activeGattClients) {
                        if (clientAddr == lastHop) continue
                        val s = gatt.getService(CampusMeshBle.TRANSPORT_SERVICE_UUID)
                        val c = s?.getCharacteristic(CampusMeshBle.MESSAGE_CHARACTERISTIC_UUID)
                        if (c != null) {
                            val ok = sendPacketWithChunking(gatt, c, packet.packetId, entity.payloadJson)
                            if (ok) forwardedToAny = true
                        }
                    }

                    val server = gattServer
                    if (server != null) {
                        val s = server.getService(CampusMeshBle.TRANSPORT_SERVICE_UUID)
                        val c = s?.getCharacteristic(CampusMeshBle.MESSAGE_CHARACTERISTIC_UUID)
                        if (c != null) {
                            for ((devAddr, dev) in connectedServerDevices) {
                                if (devAddr == lastHop) continue
                                val ok = notifyPacketWithChunking(dev, c, packet.packetId, entity.payloadJson)
                                if (ok) forwardedToAny = true
                            }
                        }
                    }

                    if (forwardedToAny) {
                        packetLastSentTime[packet.packetId] = System.currentTimeMillis()
                        messageRepository.updateMessageStatus(packet.messageId, "SENT")
                    }
                } else {
                    sentCount++
                    packetLastSentTime[packet.packetId] = System.currentTimeMillis()
                    messageRepository.updateMessageStatus(packet.messageId, "SENT")

                    val isControlPacket = packet.payload.contains("\"type\":\"DELIVERY_ACK\"") ||
                            packet.payload.contains("\"type\":\"READ_RECEIPT\"") ||
                            packet.payload.contains("\"type\":\"CALL_")

                    // Control packets don't get ACKed; message packets stay in relay store until DELIVERY_ACK arrives
                    if (isControlPacket) {
                        relayRepository.removePacket(packet.packetId)
                        packetLastSentTime.remove(packet.packetId)
                    }
                    mainHandler.post {
                        _snapshot.update { it.copy(bytesSent = it.bytesSent + entity.payloadJson.length.toLong()) }
                    }
                }
            }

            if (sentCount > 0) {
                updateRelayPacketsSnapshot()
            }
        } catch (e: Exception) {
            Timber.e(e, "Error flushing packets")
        }
    }

    private suspend fun sendPacketWithChunking(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        packetId: String,
        jsonString: String,
    ): Boolean {
        val address = gatt.device?.address ?: return false
        val mtu = deviceMtu[address] ?: 247
        val rawBytes = jsonString.toByteArray(StandardCharsets.UTF_8)
        val maxUnchunkedBytes = maxOf(20, mtu - 3)

        if (rawBytes.size <= maxUnchunkedBytes && rawBytes.size <= 240) {
            return writeCharacteristicLocked(gatt, characteristic, rawBytes)
        }

        val chunkSize = getEffectiveChunkSize(address)
        val totalChunks = (jsonString.length + chunkSize - 1) / chunkSize
        for (i in 0 until totalChunks) {
            val start = i * chunkSize
            val end = minOf(start + chunkSize, jsonString.length)
            val slice = jsonString.substring(start, end)
            val chunkStr = "PKT:$packetId:$i:$totalChunks:$slice"
            val chunkBytes = chunkStr.toByteArray(StandardCharsets.UTF_8)

            var ok = writeCharacteristicLocked(gatt, characteristic, chunkBytes)
            if (!ok) {
                delay(15)
                ok = writeCharacteristicLocked(gatt, characteristic, chunkBytes)
            }
            if (!ok) {
                Timber.w("sendPacketWithChunking failed at chunk %d/%d to %s", i + 1, totalChunks, address)
                return false
            }
            delay(5)
        }
        return true
    }

    private suspend fun notifyPacketWithChunking(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        packetId: String,
        jsonString: String,
    ): Boolean {
        val address = device.address
        val mtu = deviceMtu[address] ?: 247
        val rawBytes = jsonString.toByteArray(StandardCharsets.UTF_8)
        val maxUnchunkedBytes = maxOf(20, mtu - 3)

        if (rawBytes.size <= maxUnchunkedBytes && rawBytes.size <= 240) {
            return notifyPacketLocked(device, characteristic, rawBytes)
        }

        val chunkSize = getEffectiveChunkSize(address)
        val totalChunks = (jsonString.length + chunkSize - 1) / chunkSize
        for (i in 0 until totalChunks) {
            val start = i * chunkSize
            val end = minOf(start + chunkSize, jsonString.length)
            val slice = jsonString.substring(start, end)
            val chunkStr = "PKT:$packetId:$i:$totalChunks:$slice"
            val chunkBytes = chunkStr.toByteArray(StandardCharsets.UTF_8)

            var ok = notifyPacketLocked(device, characteristic, chunkBytes)
            if (!ok) {
                delay(15)
                ok = notifyPacketLocked(device, characteristic, chunkBytes)
            }
            if (!ok) {
                Timber.w("notifyPacketWithChunking failed at chunk %d/%d to %s", i + 1, totalChunks, address)
                return false
            }
            delay(5)
        }
        return true
    }

    @SuppressLint("MissingPermission")
    private suspend fun notifyPacketLocked(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        bytes: ByteArray,
    ): Boolean {
        val address = device.address
        val mutex = getGattMutex(address)
        return mutex.withLock {
            val deferred = CompletableDeferred<Boolean>()
            notificationDeferredMap[address] = deferred

            try {
                val initiated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gattServer?.notifyCharacteristicChanged(device, characteristic, false, bytes) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value = bytes
                    @Suppress("DEPRECATION")
                    gattServer?.notifyCharacteristicChanged(device, characteristic, false) ?: false
                }
                if (!initiated) return@withLock false
                withTimeoutOrNull(250) { deferred.await() } ?: true
            } catch (_: Exception) {
                false
            } finally {
                notificationDeferredMap.remove(address)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun writeDescriptorLocked(
        gatt: BluetoothGatt,
        descriptor: BluetoothGattDescriptor,
        value: ByteArray,
    ): Boolean {
        val address = gatt.device?.address ?: return false
        val mutex = getGattMutex(address)
        return mutex.withLock {
            val deferred = CompletableDeferred<Boolean>()
            descriptorDeferredMap[address] = deferred
            try {
                val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, value) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = value
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(descriptor)
                }
                if (!ok) return@withLock false
                withTimeoutOrNull(2_000) { deferred.await() } ?: false
            } catch (_: Exception) {
                false
            } finally {
                descriptorDeferredMap.remove(address)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun writeCharacteristicLocked(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        bytes: ByteArray,
    ): Boolean {
        val address = gatt.device?.address ?: return false
        val mutex = getGattMutex(address)
        return mutex.withLock {
            val deferred = CompletableDeferred<Boolean>()
            writeDeferredMap[address] = deferred

            try {
                val initiated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    characteristic.value = bytes
                    characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                    @Suppress("DEPRECATION")
                    gatt.writeCharacteristic(characteristic)
                }
                if (!initiated) return@withLock false
                withTimeoutOrNull(2_500) { deferred.await() } ?: false
            } catch (_: Exception) {
                false
            } finally {
                writeDeferredMap.remove(address)
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    // Reconnect & Watchdog
    // ──────────────────────────────────────────────────────────

    private fun scheduleReconnect(address: String) {
        reconnectJobs[address]?.cancel()
        reconnectJobs[address] = scope.launch {
            var delayMs = 1_000L
            while (true) {
                delay(delayMs)
                if (activeGattClients.containsKey(address) || connectedServerDevices.containsKey(address)) break
                mainHandler.post { connectAsPeer(address) }
                delayMs = minOf(delayMs * 2, 20_000L)
            }
        }
    }

    private fun cancelReconnect(address: String) {
        reconnectJobs.remove(address)?.cancel()
    }

    private fun startWatchdog(address: String) {
        cancelWatchdog(address)
        watchdogJobs[address] = scope.launch {
            delay(CONNECTION_TIMEOUT_MS)
            val isConn = activeGattClients.containsKey(address) || connectedServerDevices.containsKey(address)
            if (!isConn) {
                Timber.w("Watchdog: timeout connecting to %s — retrying", address)
                mainHandler.post {
                    val client = activeGattClients.remove(address)
                    closeClientGatt(client)
                    syncSnapshot()
                    connectAsPeer(address)
                }
            }
        }
    }

    private fun cancelWatchdog(address: String) {
        watchdogJobs.remove(address)?.cancel()
    }

    @SuppressLint("MissingPermission")
    private fun connectAsPeer(deviceAddress: String) {
        scope.launch {
            connectMutex.withLock {
                if (activeGattClients.containsKey(deviceAddress)) return@withLock
                try {
                    val device = bluetoothAdapter?.getRemoteDevice(deviceAddress) ?: return@withLock
                    val gatt = device.connectGatt(
                        context,
                        false,
                        gattClientCallback,
                        BluetoothDevice.TRANSPORT_LE,
                    )
                    if (gatt != null) {
                        activeGattClients[deviceAddress] = gatt
                        startWatchdog(deviceAddress)
                    }
                } catch (e: Exception) {
                    Timber.w(e, "connectAsPeer failed for %s", deviceAddress)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun startServer() {
        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
        try {
            val server = bluetoothManager?.openGattServer(context, serverCallback)
            if (server != null) {
                val service = BluetoothGattService(
                    CampusMeshBle.TRANSPORT_SERVICE_UUID,
                    BluetoothGattService.SERVICE_TYPE_PRIMARY,
                )
                val msgChar = BluetoothGattCharacteristic(
                    CampusMeshBle.MESSAGE_CHARACTERISTIC_UUID,
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE or
                            BluetoothGattCharacteristic.PROPERTY_READ or
                            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    BluetoothGattCharacteristic.PERMISSION_WRITE or
                            BluetoothGattCharacteristic.PERMISSION_READ,
                )
                val cccd = BluetoothGattDescriptor(
                    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                )
                msgChar.addDescriptor(cccd)

                val identityChar = BluetoothGattCharacteristic(
                    CampusMeshBle.PUBLIC_KEY_CHARACTERISTIC_UUID,
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                            BluetoothGattCharacteristic.PROPERTY_READ or
                            BluetoothGattCharacteristic.PROPERTY_NOTIFY,
                    BluetoothGattCharacteristic.PERMISSION_WRITE or
                            BluetoothGattCharacteristic.PERMISSION_READ,
                )
                val identityCccd = BluetoothGattDescriptor(
                    UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"),
                    BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE,
                )
                identityChar.addDescriptor(identityCccd)

                service.addCharacteristic(msgChar)
                service.addCharacteristic(identityChar)
                server.addService(service)
                gattServer = server
                addLog("GATT Server started")
            }
        } catch (e: Exception) {
            _snapshot.update { it.copy(lastError = e.message) }
        }
    }

    @SuppressLint("MissingPermission")
    fun stopServer() {
        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
        connectedServerDevices.clear()
        syncSnapshot()
    }

    /**
     * Called when Bluetooth is disabled or turning off.
     * Thoroughly purges dead GATT clients, connected server devices, and closes the server.
     */
    @SuppressLint("MissingPermission")
    fun onBluetoothDisabled() {
        Timber.i("DirectTransportController: Bluetooth disabled, tearing down connections")
        for ((addr, _) in activeGattClients) {
            cancelReconnect(addr)
            cancelWatchdog(addr)
        }
        for ((_, gatt) in activeGattClients) {
            closeClientGatt(gatt)
        }
        activeGattClients.clear()
        connectedServerDevices.clear()
        exchangedIdentityPeers.clear()
        deviceMtu.clear()
        gattMutexMap.clear()
        writeDeferredMap.values.forEach { it.complete(false) }
        writeDeferredMap.clear()
        descriptorDeferredMap.values.forEach { it.complete(false) }
        descriptorDeferredMap.clear()
        notificationDeferredMap.values.forEach { it.complete(false) }
        notificationDeferredMap.clear()

        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null

        peerRegistry.clear()

        mainHandler.post {
            syncSnapshot()
            addLog("Bluetooth disabled: cleaned up state")
        }
    }

    /**
     * Called when Bluetooth is turned back ON.
     * Recreates the GATT server from scratch, resets discovery, and schedules packet flush.
     */
    @SuppressLint("MissingPermission")
    fun onBluetoothRestarted() {
        Timber.i("DirectTransportController: Bluetooth restarted, recreating server & mesh")
        onBluetoothDisabled()
        startServer()
        scope.launch {
            delay(500)
            flushPendingPackets()
        }
    }

    fun connectToPeer(deviceAddress: String, peerNodeId: String, peerLabel: String) {
        if (deviceAddress.isBlank()) {
            Timber.w("connectToPeer: blank address for %s", peerNodeId)
            _snapshot.update { it.copy(peerNodeId = peerNodeId, peerLabel = peerLabel) }
            return
        }

        addressToNodeId[deviceAddress] = peerNodeId
        nodeIdToAddress[peerNodeId] = deviceAddress

        cancelReconnect(deviceAddress)
        cancelWatchdog(deviceAddress)

        _snapshot.update {
            it.copy(
                peerLabel = peerLabel,
                peerAddress = deviceAddress,
                peerNodeId = peerNodeId,
                lastError = null,
            )
        }
        syncSnapshot()

        startWatchdog(deviceAddress)
        connectAsPeer(deviceAddress)
    }

    fun reconnectToPeer() {
        val addr = _snapshot.value.peerAddress ?: return
        val nodeId = _snapshot.value.peerNodeId ?: return
        val label = _snapshot.value.peerLabel ?: nodeId.take(8).uppercase()
        connectToPeer(addr, nodeId, label)
    }

    fun sendMessage(text: String, targetPeerNodeId: String? = null) {
        val currentPeerNodeId = targetPeerNodeId ?: _snapshot.value.peerNodeId ?: return
        val currentPeerLabel = _snapshot.value.peerLabel ?: currentPeerNodeId.take(8).uppercase()
        val localNodeId = localNodeIdStore.nodeId.toString()
        val now = System.currentTimeMillis()
        val messageId = UUID.randomUUID().toString()
        val packetId = UUID.randomUUID().toString()

        _snapshot.update { it.copy(peerNodeId = currentPeerNodeId, peerLabel = currentPeerLabel) }

        scope.launch {
            val peer = peerRepository.getPeer(currentPeerNodeId)
            val recipientPubKey = peer?.publicKeyBase64

            val encryptedPayload = if (!recipientPubKey.isNullOrBlank()) {
                nodeKeyManager.encrypt(text, recipientPubKey) ?: text
            } else {
                text
            }

            val packet = MeshPacket(
                protocolVersion = 1,
                packetId = packetId,
                messageId = messageId,
                sourceId = localNodeId,
                destinationId = currentPeerNodeId,
                timestamp = now,
                ttl = 7,
                hopCount = 0,
                payload = encryptedPayload,
            )

            messageRepository.saveMessage(
                messageId = messageId,
                senderId = "local",
                recipientId = currentPeerNodeId,
                content = text,
                timestamp = now,
                status = "PENDING",
            )

            val payloadJson = PacketProtocol.serialize(packet)
            relayRepository.storePacket(
                packetId = packetId,
                destinationId = currentPeerNodeId,
                payloadJson = payloadJson,
                ttl = 7,
                createdAt = now,
                expiresAt = now + 86_400_000L,
            )

            updateRelayPacketsSnapshot()
            flushPendingPackets()
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        for ((addr, _) in activeGattClients) {
            cancelReconnect(addr)
            cancelWatchdog(addr)
        }
        for ((_, gatt) in activeGattClients) {
            closeClientGatt(gatt)
        }
        activeGattClients.clear()
        syncSnapshot()
    }

    @SuppressLint("MissingPermission")
    private fun closeClientGatt(gatt: BluetoothGatt?) {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {}
    }

    private fun syncSnapshot() {
        val clientAddrs = activeGattClients.keys().toList()
        val serverAddrs = connectedServerDevices.keys().toList()
        val allAddrs = (clientAddrs + serverAddrs).toSet()

        val allNodeIds = allAddrs.mapNotNull { addressToNodeId[it] }.toSet()

        val activePeer = _snapshot.value.peerNodeId
        val activeAddr = _snapshot.value.peerAddress

        val isTargetConnected = (activePeer != null && allNodeIds.contains(activePeer)) ||
                (activeAddr != null && allAddrs.contains(activeAddr))

        val state = when {
            isTargetConnected -> TransportConnectionState.Connected
            allAddrs.isNotEmpty() -> TransportConnectionState.Connected
            else -> TransportConnectionState.Disconnected
        }

        _snapshot.update {
            it.copy(
                connectionState = state,
                connectedPeerNodeIds = allNodeIds,
                connectedDeviceAddresses = allAddrs,
            )
        }
    }

    fun isPeerDirectlyConnected(nodeId: String): Boolean {
        return _snapshot.value.connectedPeerNodeIds.contains(nodeId)
    }

    fun isAddressDirectlyConnected(address: String): Boolean {
        return _snapshot.value.connectedDeviceAddresses.contains(address)
    }

    private suspend fun updateRelayPacketsSnapshot() {
        val packets = relayRepository.getAllPackets()
        _snapshot.update {
            it.copy(
                relayPackets = packets,
                packetsForwarded = meshMetricsTracker.getPacketsForwarded(),
                duplicatesDiscarded = meshMetricsTracker.getDuplicatesDiscarded(),
                averageHopCount = meshMetricsTracker.getAverageHopCount(),
                averageDeliveryTimeMs = meshMetricsTracker.getAverageDeliveryTimeMs(),
                expiredCount = meshMetricsTracker.getExpiredCount(),
            )
        }
    }

    private fun addLog(line: String) {
        _snapshot.update {
            it.copy(logs = (listOf(line) + it.logs).take(30))
        }
    }

    companion object {
        private const val CONNECTION_TIMEOUT_MS = 4_000L
    }
}
