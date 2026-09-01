package com.campusmesh.ui.debug

import com.campusmesh.ble.DiscoverySnapshot
import com.campusmesh.mesh.MeshState
import com.campusmesh.permissions.PermissionStatus
import com.campusmesh.platform.DeviceStatus
import com.campusmesh.transport.DirectTransportSnapshot

import com.campusmesh.db.PeerEntity

data class DebugStatusUiState(
    val device: DeviceStatus,
    val permissions: List<PermissionStatus>,
    val meshState: MeshState,
    val discovery: DiscoverySnapshot,
    val locationEnabled: Boolean,
    val directTransport: DirectTransportSnapshot = DirectTransportSnapshot(),
    val phaseLabel: String = "Developer & Network Diagnostics",
    val knownPeers: List<PeerEntity> = emptyList(),
    // Demonstration metrics
    val deviceId: String = "",
    val nearbyNodes: Int = 0,
    val activeConnections: Int = 0,
    val packetsStored: Long = 0L,
    val packetsForwarded: Long = 0L,
    val duplicatesBlocked: Long = 0L,
    val messagesDelivered: Int = 0,
    val bleStatus: String = "Unknown",
    val scanStatus: String = "Stopped",
    val advertiseStatus: String = "Stopped",
    val routingActivity: String = "Idle",
)
