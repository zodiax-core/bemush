package com.campusmesh.ui.peers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.campusmesh.ble.BleDiscoveryController
import com.campusmesh.ble.NearbyPeer
import com.campusmesh.data.PeerRepository
import com.campusmesh.db.PeerEntity
import com.campusmesh.identity.LocalNodeIdStore
import com.campusmesh.transport.DirectTransportController
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PeersUiState(
    val nearbyPeers: List<NearbyPeer> = emptyList(),
    val knownPeers: List<PeerEntity> = emptyList(),
    val allPeers: List<PeerEntity> = emptyList(),
    val nowEpochMs: Long = System.currentTimeMillis(),
    val localNodeId: String = "",
    val isDiscoveryActive: Boolean = false,
) {
    fun resolveLabel(nodeId: String, fallback: String): String {
        val peer = allPeers.find { it.nodeId == nodeId }
        return peer?.displayName ?: fallback
    }
}

@HiltViewModel
class PeersViewModel @Inject constructor(
    private val discoveryController: BleDiscoveryController,
    private val peerRepository: PeerRepository,
    private val transportController: DirectTransportController,
    localNodeIdStore: LocalNodeIdStore,
) : ViewModel() {

    private val localNodeId = localNodeIdStore.nodeId.toString()
    private val nowMs = MutableStateFlow(System.currentTimeMillis())

    val uiState: StateFlow<PeersUiState> = combine(
        discoveryController.snapshot,
        peerRepository.allPeers,
        nowMs,
    ) { discovery, knownPeers, now ->
        PeersUiState(
            nearbyPeers = discovery.peers,
            knownPeers = knownPeers.filter { p ->
                discovery.peers.none { live -> live.nodeId.toString() == p.nodeId }
            },
            allPeers = knownPeers,
            nowEpochMs = now,
            localNodeId = localNodeId,
            isDiscoveryActive = discovery.wantedRunning,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = PeersUiState(localNodeId = localNodeId),
    )

    init {
        discoveryController.setForeground(true)
        discoveryController.setWantedRunning(true)
        transportController.startServer()

        viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                discoveryController.tick()
                nowMs.value = System.currentTimeMillis()
            }
        }
    }

    fun connectAndSync(deviceAddress: String, nodeId: String, label: String) {
        transportController.connectToPeer(deviceAddress, nodeId, label)
    }
}
