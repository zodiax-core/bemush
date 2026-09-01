package com.campusmesh.ui.debug

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campusmesh.ble.BleDiscoveryController
import com.campusmesh.ble.DiscoverySnapshot
import com.campusmesh.ble.RadioOpState
import com.campusmesh.mesh.MeshState
import com.campusmesh.mesh.MeshMetricsTracker
import com.campusmesh.permissions.PermissionStatusProvider
import com.campusmesh.platform.DeviceStatusProvider
import com.campusmesh.platform.LocationStatusProvider
import com.campusmesh.transport.DirectTransportController
import com.campusmesh.transport.DirectTransportSnapshot
import com.campusmesh.transport.TransportConnectionState
import com.campusmesh.data.MessageRepository
import com.campusmesh.data.PeerRepository
import com.campusmesh.data.RelayRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DebugStatusViewModel @Inject constructor(
    private val deviceStatusProvider: DeviceStatusProvider,
    private val permissionStatusProvider: PermissionStatusProvider,
    private val locationStatusProvider: LocationStatusProvider,
    private val discoveryController: BleDiscoveryController,
    private val directTransportController: DirectTransportController,
    private val peerRepository: PeerRepository,
    private val relayRepository: RelayRepository,
    private val messageRepository: MessageRepository,
    private val meshMetricsTracker: MeshMetricsTracker,
) : ViewModel() {

    private val _uiState = MutableStateFlow(readState(discoveryController.snapshot.value, directTransportController.snapshot.value, emptyList()))
    val uiState: StateFlow<DebugStatusUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            discoveryController.snapshot.collect { snapshot ->
                _uiState.update { readState(snapshot, directTransportController.snapshot.value, it.knownPeers) }
            }
        }
        viewModelScope.launch {
            directTransportController.snapshot.collect { transportSnapshot ->
                _uiState.update { readState(discoveryController.snapshot.value, transportSnapshot, it.knownPeers) }
            }
        }
        viewModelScope.launch {
            peerRepository.allPeers.collect { peers ->
                _uiState.update { it.copy(knownPeers = peers) }
            }
        }
        viewModelScope.launch {
            messageRepository.allMessages.collect { messages ->
                _uiState.update { it.copy(messagesDelivered = messages.size) }
            }
        }
        viewModelScope.launch {
            relayRepository.allPackets.collect { packets ->
                _uiState.update { it.copy(packetsStored = packets.size.toLong()) }
            }
        }
        // Tick loop: only update time-sensitive fields (peer last-seen ages, live metrics).
        // Bug 6 fix: do NOT reassign bleStatus/scanStatus/advertiseStatus/connectionState here —
        // those are event-driven (updated by collect{} flows above) and reassigning them every
        // second was causing visible UI flicker.
        viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                discoveryController.tick()
                val discoverySnap = discoveryController.snapshot.value
                val transportSnap = directTransportController.snapshot.value
                _uiState.update { current ->
                    current.copy(
                        // Time-sensitive: refresh device/permission/location state periodically.
                        deviceId = discoverySnap.localNodeLabel,
                        device = deviceStatusProvider.current(),
                        permissions = permissionStatusProvider.currentStatuses(),
                        locationEnabled = locationStatusProvider.isLocationEnabled(),
                        // Counts derived from live state.
                        nearbyNodes = discoverySnap.peers.size,
                        activeConnections = if (transportSnap.connectionState == TransportConnectionState.Connected) 1 else 0,
                        packetsForwarded = meshMetricsTracker.getPacketsForwarded(),
                        duplicatesBlocked = meshMetricsTracker.getDuplicatesDiscarded(),
                        packetsStored = current.packetsStored,
                        messagesDelivered = current.messagesDelivered,
                        // BLE status labels derived from actual RadioOpState — stable strings,
                        // only change when the radio state actually changes.
                        bleStatus = bleStatusLabel(transportSnap.connectionState),
                        scanStatus = radioStateLabel(discoverySnap.scan),
                        advertiseStatus = radioStateLabel(discoverySnap.advertise),
                        routingActivity = if (current.packetsForwarded > 0) "Active" else "Idle",
                    )
                }
            }
        }
    }

    fun refresh() {
        discoveryController.refresh()
        _uiState.update { readState(discoveryController.snapshot.value, directTransportController.snapshot.value, it.knownPeers) }
    }

    fun onForeground() {
        discoveryController.setForeground(true)
        directTransportController.startServer()
        refresh()
    }

    fun onBackground() {
        discoveryController.setForeground(false)
    }

    fun startDiscovery() {
        discoveryController.setWantedRunning(true)
        directTransportController.startServer()
    }

    fun stopDiscovery() {
        discoveryController.setWantedRunning(false)
        directTransportController.disconnect()
    }

    fun connectToPeer(deviceAddress: String, peerNodeId: String, peerLabel: String) {
        directTransportController.connectToPeer(deviceAddress, peerNodeId, peerLabel)
    }

    fun sendMessage(text: String) {
        directTransportController.sendMessage(text)
    }

    fun disconnectTransport() {
        directTransportController.disconnect()
    }

    override fun onCleared() {
        discoveryController.release()
        directTransportController.disconnect()
        super.onCleared()
    }

    private fun readState(
        snapshot: DiscoverySnapshot,
        transportSnapshot: DirectTransportSnapshot,
        knownPeers: List<com.campusmesh.db.PeerEntity>
    ): DebugStatusUiState {
        return DebugStatusUiState(
            device = deviceStatusProvider.current(),
            permissions = permissionStatusProvider.currentStatuses(),
            meshState = MeshState.Active,
            discovery = snapshot,
            locationEnabled = locationStatusProvider.isLocationEnabled(),
            directTransport = transportSnapshot,
            knownPeers = knownPeers,
        )
    }
}

/** Human-readable BLE connection status label (stable — no flicker). */
private fun bleStatusLabel(state: TransportConnectionState): String = when (state) {
    TransportConnectionState.Disconnected -> "Disconnected"
    TransportConnectionState.Connecting   -> "Connecting…"
    TransportConnectionState.Connected    -> "Connected ✅"
    TransportConnectionState.Failed       -> "Failed ❌"
}

/** Human-readable BLE radio (scan/advertise) state label. */
private fun radioStateLabel(state: RadioOpState): String = when (state) {
    RadioOpState.Idle     -> "Idle"
    RadioOpState.Starting -> "Starting…"
    RadioOpState.Running  -> "Running ✅"
    RadioOpState.Blocked  -> "Blocked ⚠️"
    RadioOpState.Failed   -> "Failed ❌"
}
