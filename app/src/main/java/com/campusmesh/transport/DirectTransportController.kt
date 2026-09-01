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
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.campusmesh.ble.CampusMeshBle
import com.campusmesh.ble.PeerRegistry
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.UUID
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

    private val writeMutex = Mutex()
    private val connectMutex = Mutex()

    private var gattServer: BluetoothGattServer? = null
    private var activeGattClient: BluetoothGatt? = null
    @Volatile private var activeClientPeerAddress: String? = null
    @Volatile private var connectedServerDevice: BluetoothDevice? = null
    private var writeDeferred: CompletableDeferred<Boolean>? = null
    private var reconnectJob: Job? = null

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

        // Live Push: When local profile updates (name or photo), push identity to connected peer.
        scope.launch {
            profileManager.localProfile.collect {
                pushUpdatedIdentityToConnectedPeers()
            }
        }

        // Auto Fetch: When BLE scanner discovers new peer lacking display name, auto-fetch identity over GATT.
        scope.launch {
            peerRegistry.peers.collect { nearbyPeers ->
                autoFetchIdentityForDiscoveredPeers(nearbyPeers)
            }
        }
    }

    private suspend fun autoFetchIdentityForDiscoveredPeers(nearbyPeers: List<com.campusmesh.ble.NearbyPeer>) {
        if (_snapshot.value.connectionState == TransportConnectionState.Connected) return
        for (peer in nearbyPeers) {
            val dbPeer = peerRepository.getPeer(peer.nodeId.toString())
            if (dbPeer == null || dbPeer.displayName.isNullOrBlank()) {
                Timber.i("Auto-fetching identity over GATT for discovered peer: %s (%s)", peer.nodeId, peer.deviceAddress)
                connectToPeer(
                    deviceAddress = peer.deviceAddress,
                    peerNodeId = peer.nodeId.toString(),
                    peerLabel = peer.shortLabel,
                )
                break
            }
        }
    }

    private fun pushUpdatedIdentityToConnectedPeers() {
        val client = activeGattClient ?: return
        scope.launch {
            try {
                val service = client.getService(CampusMeshBle.TRANSPORT_SERVICE_UUID)
                val identityChar = service?.getCharacteristic(CampusMeshBle.PUBLIC_KEY_CHARACTERISTIC_UUID)
                if (identityChar != null) {
                    val identityJson = buildIdentityPayload()
                    val bytes = identityJson.toByteArray(StandardCharsets.UTF_8)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        client.writeCharacteristic(identityChar, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                    } else {
                        @Suppress("DEPRECATION")
                        identityChar.value = bytes
                        @Suppress("DEPRECATION")
                        client.writeCharacteristic(identityChar)
                    }
                    Timber.i("Pushed updated identity payload to connected peer over BLE")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to push updated identity payload")
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    // GATT Server
    // ──────────────────────────────────────────────────────────

    private val serverCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice?, status: Int, newState: Int) {
            super.onConnectionStateChange(device, status, newState)
            val address = device?.address ?: return
            Timber.i("GATT Server state: device=%s status=%d newState=%d", address, status, newState)

            mainHandler.post {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectedServerDevice = device
                    addLog("Peer connected to server: $address")
                    connectReverseClient(address)
                    updateSnapshot(
                        connectionState = TransportConnectionState.Connected,
                        peerAddress = address,
                        peerNodeId = _snapshot.value.peerNodeId ?: address,
                    )
                    flushPendingPackets()
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (device.address == connectedServerDevice?.address) {
                        connectedServerDevice = null
                        addLog("Peer disconnected from server: $address")
                        checkAndUpdateDisconnected()
                    }
                }
            }
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
                        Timber.i("Received identity payload from: $address")
                        scope.launch { handleIdentityPayload(payload, address) }
                    }
                    respondToWrite(device, requestId, responseNeeded, value)
                }

                CampusMeshBle.MESSAGE_CHARACTERISTIC_UUID -> {
                    if (value != null) {
                        handleIncomingPacket(value, address)
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
    // GATT Client
    // ──────────────────────────────────────────────────────────

    private val gattClientCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)
            val address = gatt?.device?.address ?: "unknown"
            Timber.i("GATT Client state: address=%s status=%d newState=%d", address, status, newState)

            mainHandler.post {
                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                    activeClientPeerAddress = address
                    addLog("Connected as client to: $address")
                    updateSnapshot(
                        connectionState = TransportConnectionState.Connected,
                        peerAddress = address,
                    )
                    cancelReconnect()
                    try {
                        gatt?.requestMtu(512)
                    } catch (e: SecurityException) {
                        scope.launch { gatt?.discoverServices() }
                    }
                } else {
                    val wasOurPeer = (address == activeClientPeerAddress)
                    if (wasOurPeer) activeClientPeerAddress = null
                    addLog("Client disconnected from: $address (status=$status)")
                    writeDeferred?.complete(false)
                    closeClientGatt(gatt)
                    checkAndUpdateDisconnected()

                    if (wasOurPeer && _snapshot.value.peerAddress == address) {
                        scheduleReconnect(address)
                    }
                }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)
            mainHandler.post { addLog("MTU: $mtu") }
            scope.launch {
                try {
                    @Suppress("MissingPermission")
                    gatt?.discoverServices()
                } catch (e: SecurityException) {
                    Timber.e(e, "discoverServices failed")
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
            super.onServicesDiscovered(gatt, status)
            if (status != BluetoothGatt.GATT_SUCCESS || gatt == null) return
            addLog("Services discovered on: ${gatt.device?.address}")

            scope.launch {
                try {
                    val service = gatt.getService(CampusMeshBle.TRANSPORT_SERVICE_UUID)
                    val msgChar = service?.getCharacteristic(CampusMeshBle.MESSAGE_CHARACTERISTIC_UUID)
                    if (msgChar != null) {
                        gatt.setCharacteristicNotification(msgChar, true)
                        val cccdUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
                        val descriptor = msgChar.getDescriptor(cccdUuid)
                        if (descriptor != null) {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                            } else {
                                @Suppress("DEPRECATION")
                                descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                                @Suppress("DEPRECATION")
                                gatt.writeDescriptor(descriptor)
                            }
                        }
                    }

                    // Send identity (public key + display name + avatar Base64/hash).
                    val identityChar = service?.getCharacteristic(CampusMeshBle.PUBLIC_KEY_CHARACTERISTIC_UUID)
                    if (identityChar != null) {
                        val identityJson = buildIdentityPayload()
                        val bytes = identityJson.toByteArray(StandardCharsets.UTF_8)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            gatt.writeCharacteristic(identityChar, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
                        } else {
                            @Suppress("DEPRECATION")
                            identityChar.value = bytes
                            @Suppress("DEPRECATION")
                            gatt.writeCharacteristic(identityChar)
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error during post-discovery setup")
                }
                flushPendingPackets()
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            val success = (status == BluetoothGatt.GATT_SUCCESS)
            if (!success) {
                addLog("Write failed ($status)")
                updateSnapshot(lastError = "Write failed ($status)")
            }
            writeDeferred?.complete(success)
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            super.onCharacteristicChanged(gatt, characteristic, value)
            handleIncomingPacket(value, gatt.device?.address ?: "unknown")
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?) {
            super.onCharacteristicChanged(gatt, characteristic)
            val value = characteristic?.value
            if (value != null) {
                handleIncomingPacket(value, gatt?.device?.address ?: "unknown")
            }
        }
    }

    // ──────────────────────────────────────────────────────────
    // Identity & Avatar Exchange
    // ──────────────────────────────────────────────────────────

    private fun buildIdentityPayload(): String {
        val localProf = profileManager.localProfile.value
        val avatarFile = localProf.avatarPath?.let { File(it) }
        val avatarBase64 = avatarFile?.takeIf { it.exists() }?.let { ImageUtils.encodeFileToBase64(it) }
        val avatarHash = avatarFile?.takeIf { it.exists() }?.let { ImageUtils.computeFileHash(it) } ?: ""

        val obj = JSONObject()
        obj.put("publicKey", nodeKeyManager.publicKeyBase64)
        obj.put("displayName", localProf.displayName)
        obj.put("nodeId", localNodeIdStore.nodeId.toString())
        obj.put("avatarHash", avatarHash)
        if (!avatarBase64.isNullOrBlank()) {
            obj.put("avatarBase64", avatarBase64)
        }
        return obj.toString()
    }

    private suspend fun handleIdentityPayload(payload: String, deviceAddress: String) {
        try {
            val obj = JSONObject(payload)
            val pubKey = obj.optString("publicKey").ifBlank { null }
            val displayName = obj.optString("displayName").ifBlank { null }
            val nodeId = obj.optString("nodeId").ifBlank { deviceAddress }
            val avatarHash = obj.optString("avatarHash").ifBlank { null }
            val avatarBase64 = obj.optString("avatarBase64").ifBlank { null }

            val existingPeer = peerRepository.getPeer(nodeId)
            var savedAvatarPath = existingPeer?.avatarPath

            // Smart Avatar Check: ONLY save/overwrite image if hash changed!
            if (!avatarHash.isNullOrBlank() && (existingPeer?.avatarHash != avatarHash || savedAvatarPath == null || !File(savedAvatarPath).exists())) {
                if (!avatarBase64.isNullOrBlank()) {
                    val savedFile = ImageUtils.saveBase64Avatar(context, avatarBase64, nodeId, previousFilePath = existingPeer?.avatarPath)
                    savedAvatarPath = savedFile?.absolutePath
                }
            }

            peerRepository.upsertPeer(
                nodeId = nodeId,
                deviceAddress = deviceAddress,
                rssiDbm = -50,
                lastSeenEpochMs = System.currentTimeMillis(),
                publicKeyBase64 = pubKey,
                displayName = displayName,
                avatarPath = savedAvatarPath,
                avatarHash = avatarHash,
            )

            mainHandler.post {
                if (_snapshot.value.peerAddress == deviceAddress) {
                    updateSnapshot(
                        peerNodeId = nodeId,
                        peerLabel = displayName ?: nodeId.take(8).uppercase(),
                    )
                }
            }

            if (activeChatPeerId == nodeId) {
                markConversationAsRead(nodeId)
            }

            flushPendingPackets()
        } catch (e: Exception) {
            Timber.e(e, "Failed to parse identity payload")
        }
    }

    // ──────────────────────────────────────────────────────────
    // Incoming Packet Handling & Read Receipts
    // ──────────────────────────────────────────────────────────

    private fun handleIncomingPacket(value: ByteArray, address: String) {
        val payloadString = String(value, StandardCharsets.UTF_8)
        val receivedBytes = value.size.toLong()

        mainHandler.post {
            _snapshot.update {
                it.copy(
                    bytesReceived = it.bytesReceived + receivedBytes,
                    lastReceivedMessage = payloadString.take(50),
                )
            }
        }

        scope.launch {
            val packet = PacketProtocol.deserialize(payloadString) ?: return@launch
            if (!PacketProtocol.isValid(packet)) return@launch

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

            if (packet.destinationId == localNodeId) {
                val decrypted = try { nodeKeyManager.decrypt(packet.payload) } catch (_: Exception) { null }
                val content = decrypted?.plaintext ?: packet.payload
                val senderPubKey = decrypted?.senderPublicKey

                if (content.startsWith("{") && content.contains("\"type\":\"READ_RECEIPT\"")) {
                    Timber.i("Received READ_RECEIPT from %s", packet.sourceId)
                    messageRepository.updateAllOutgoingStatusForPeer(packet.sourceId, "SEEN")
                    updateRelayPacketsSnapshot()
                    return@launch
                }

                if (senderPubKey != null) {
                    peerRepository.upsertPeer(
                        nodeId = packet.sourceId,
                        deviceAddress = address,
                        rssiDbm = -50,
                        lastSeenEpochMs = System.currentTimeMillis(),
                        publicKeyBase64 = senderPubKey,
                    )
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

                if (isCurrentChatOpen) {
                    sendReadReceiptPacket(packet.sourceId)
                } else {
                    val peer = peerRepository.getPeer(packet.sourceId)
                    val senderName = peer?.displayName ?: packet.sourceId.take(8).uppercase()
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
                if (packet.ttl > 1) {
                    val updated = packet.copy(ttl = packet.ttl - 1, hopCount = packet.hopCount + 1)
                    val updatedJson = PacketProtocol.serialize(updated)
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
                    flushPendingPackets()
                }
            }
        }
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

        val peer = peerRepository.getPeer(peerNodeId)
        val encryptedPayload = peer?.publicKeyBase64?.let { nodeKeyManager.encrypt(controlPayload, it) } ?: controlPayload

        val packet = MeshPacket(
            protocolVersion = 1,
            packetId = UUID.randomUUID().toString(),
            messageId = UUID.randomUUID().toString(),
            sourceId = localNodeId,
            destinationId = peerNodeId,
            timestamp = now,
            ttl = 3,
            hopCount = 0,
            payload = encryptedPayload,
        )

        val payloadJson = PacketProtocol.serialize(packet)
        relayRepository.storePacket(
            packetId = packet.packetId,
            destinationId = peerNodeId,
            payloadJson = payloadJson,
            ttl = 3,
            createdAt = now,
            expiresAt = now + 300_000L,
        )
        flushPendingPackets()
    }

    // ──────────────────────────────────────────────────────────
    // Packet Flushing
    // ──────────────────────────────────────────────────────────

    private fun flushPendingPackets() {
        scope.launch {
            writeMutex.withLock { flushPendingPacketsLocked() }
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun flushPendingPacketsLocked() {
        val gattClient = activeGattClient
        val serverDevice = connectedServerDevice
        val isClientConnected = gattClient != null &&
                _snapshot.value.connectionState == TransportConnectionState.Connected
        val isServerConnected = gattServer != null && serverDevice != null

        if (!isClientConnected && !isServerConnected) return

        try {
            val localNodeId = localNodeIdStore.nodeId.toString()
            val allPackets = relayRepository.getAllPackets()
            if (allPackets.isEmpty()) return

            var sentCount = 0

            for (entity in allPackets) {
                val packet = PacketProtocol.deserialize(entity.payloadJson) ?: continue
                if (packet.destinationId == localNodeId) continue

                val bytes = entity.payloadJson.toByteArray(StandardCharsets.UTF_8)
                var success = false

                if (isClientConnected && gattClient != null) {
                    val service = gattClient.getService(CampusMeshBle.TRANSPORT_SERVICE_UUID)
                    val char = service?.getCharacteristic(CampusMeshBle.MESSAGE_CHARACTERISTIC_UUID)
                    if (char != null) {
                        success = writePacketSync(gattClient, char, bytes)
                    }
                }

                if (!success && isServerConnected && serverDevice != null && gattServer != null) {
                    val service = gattServer?.getService(CampusMeshBle.TRANSPORT_SERVICE_UUID)
                    val char = service?.getCharacteristic(CampusMeshBle.MESSAGE_CHARACTERISTIC_UUID)
                    if (char != null) {
                        success = notifyPacket(serverDevice, char, bytes)
                    }
                }

                if (success) {
                    sentCount++
                    relayRepository.removePacket(packet.packetId)
                    messageRepository.updateMessageStatus(packet.messageId, "SENT")
                    mainHandler.post {
                        _snapshot.update { it.copy(bytesSent = it.bytesSent + bytes.size) }
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

    @SuppressLint("MissingPermission")
    private fun notifyPacket(
        device: BluetoothDevice,
        characteristic: BluetoothGattCharacteristic,
        bytes: ByteArray,
    ): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gattServer?.notifyCharacteristicChanged(device, characteristic, false, bytes) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            characteristic.value = bytes
            @Suppress("DEPRECATION")
            gattServer?.notifyCharacteristicChanged(device, characteristic, false) ?: false
        }
    } catch (_: Exception) { false }

    @SuppressLint("MissingPermission")
    private suspend fun writePacketSync(
        gatt: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        bytes: ByteArray,
    ): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        writeDeferred = deferred

        return try {
            val initiated = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(characteristic, bytes, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = bytes
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(characteristic)
            }
            if (!initiated) return false
            withTimeoutOrNull(5_000) { deferred.await() } ?: false
        } catch (_: Exception) { false } finally {
            writeDeferred = null
        }
    }

    private fun scheduleReconnect(address: String) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            var delayMs = 3_000L
            while (true) {
                delay(delayMs)
                if (activeClientPeerAddress == address) break
                mainHandler.post { connectAsPeer(address) }
                delayMs = minOf(delayMs * 2, 30_000L)
            }
        }
    }

    private fun cancelReconnect() {
        reconnectJob?.cancel()
        reconnectJob = null
    }

    private fun connectReverseClient(deviceAddress: String) {
        if (activeClientPeerAddress == deviceAddress) return
        connectAsPeer(deviceAddress)
    }

    @SuppressLint("MissingPermission")
    private fun connectAsPeer(deviceAddress: String) {
        scope.launch {
            connectMutex.withLock {
                if (activeClientPeerAddress == deviceAddress) return@withLock
                try {
                    val device = bluetoothAdapter?.getRemoteDevice(deviceAddress) ?: return@withLock
                    closeClientGatt(activeGattClient)
                    activeGattClient = device.connectGatt(
                        context,
                        false,
                        gattClientCallback,
                        BluetoothDevice.TRANSPORT_LE,
                    )
                } catch (_: Exception) {}
            }
        }
    }

    private fun checkAndUpdateDisconnected() {
        if (activeClientPeerAddress == null && connectedServerDevice == null) {
            updateSnapshot(connectionState = TransportConnectionState.Disconnected)
        }
    }

    @SuppressLint("MissingPermission")
    fun startServer() {
        if (gattServer != null) return
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
                            BluetoothGattCharacteristic.PROPERTY_READ,
                    BluetoothGattCharacteristic.PERMISSION_WRITE or
                            BluetoothGattCharacteristic.PERMISSION_READ,
                )

                service.addCharacteristic(msgChar)
                service.addCharacteristic(identityChar)
                server.addService(service)
                gattServer = server
                addLog("GATT Server started")
            }
        } catch (e: Exception) {
            updateSnapshot(lastError = e.message)
        }
    }

    @SuppressLint("MissingPermission")
    fun stopServer() {
        try { gattServer?.close() } catch (_: Exception) {}
        gattServer = null
        connectedServerDevice = null
    }

    fun connectToPeer(deviceAddress: String, peerNodeId: String, peerLabel: String) {
        cancelReconnect()
        updateSnapshot(
            connectionState = TransportConnectionState.Connecting,
            peerLabel = peerLabel,
            peerAddress = deviceAddress,
            peerNodeId = peerNodeId,
            lastError = null,
        )
        connectAsPeer(deviceAddress)
    }

    fun sendMessage(text: String, targetPeerNodeId: String? = null) {
        val currentPeerNodeId = targetPeerNodeId ?: _snapshot.value.peerNodeId ?: return
        val currentPeerLabel = _snapshot.value.peerLabel ?: currentPeerNodeId.take(8).uppercase()
        val localNodeId = localNodeIdStore.nodeId.toString()
        val now = System.currentTimeMillis()
        val messageId = UUID.randomUUID().toString()
        val packetId = UUID.randomUUID().toString()

        updateSnapshot(peerNodeId = currentPeerNodeId, peerLabel = currentPeerLabel)

        scope.launch {
            val peer = peerRepository.getPeer(currentPeerNodeId)
            val recipientPubKey = peer?.publicKeyBase64

            val encryptedPayload = if (recipientPubKey != null) {
                nodeKeyManager.encrypt(text, recipientPubKey) ?: text
            } else text

            val packet = MeshPacket(
                protocolVersion = 1,
                packetId = packetId,
                messageId = messageId,
                sourceId = localNodeId,
                destinationId = currentPeerNodeId,
                timestamp = now,
                ttl = 5,
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
                ttl = 5,
                createdAt = now,
                expiresAt = now + 86_400_000L,
            )

            updateRelayPacketsSnapshot()
            flushPendingPackets()
        }
    }

    @SuppressLint("MissingPermission")
    fun disconnect() {
        cancelReconnect()
        closeClientGatt(activeGattClient)
        activeClientPeerAddress = null
        connectedServerDevice = null
        updateSnapshot(
            connectionState = TransportConnectionState.Disconnected,
            peerLabel = null,
            peerAddress = null,
            peerNodeId = null,
        )
    }

    @SuppressLint("MissingPermission")
    private fun closeClientGatt(gatt: BluetoothGatt?) {
        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {}
        if (gatt === activeGattClient) activeGattClient = null
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

    private fun updateSnapshot(
        connectionState: TransportConnectionState = _snapshot.value.connectionState,
        peerLabel: String? = _snapshot.value.peerLabel,
        peerAddress: String? = _snapshot.value.peerAddress,
        peerNodeId: String? = _snapshot.value.peerNodeId,
        bytesSent: Long = _snapshot.value.bytesSent,
        bytesReceived: Long = _snapshot.value.bytesReceived,
        lastReceivedMessage: String? = _snapshot.value.lastReceivedMessage,
        lastError: String? = _snapshot.value.lastError,
        logs: List<String> = _snapshot.value.logs,
        relayPackets: List<RelayPacketEntity> = _snapshot.value.relayPackets,
    ) {
        _snapshot.update {
            it.copy(
                connectionState = connectionState,
                peerLabel = peerLabel,
                peerAddress = peerAddress,
                peerNodeId = peerNodeId,
                bytesSent = bytesSent,
                bytesReceived = bytesReceived,
                lastReceivedMessage = lastReceivedMessage,
                lastError = lastError,
                logs = logs,
                relayPackets = relayPackets,
                packetsForwarded = meshMetricsTracker.getPacketsForwarded(),
                duplicatesDiscarded = meshMetricsTracker.getDuplicatesDiscarded(),
                averageHopCount = meshMetricsTracker.getAverageHopCount(),
                averageDeliveryTimeMs = meshMetricsTracker.getAverageDeliveryTimeMs(),
                expiredCount = meshMetricsTracker.getExpiredCount(),
            )
        }
    }
}
