package com.campusmesh.ble

import java.util.UUID

enum class RadioOpState {
    Idle,
    Starting,
    Running,
    Blocked,
    Failed,
}

data class DiscoverySnapshot(
    val localNodeId: UUID,
    val localNodeLabel: String,
    val wantedRunning: Boolean,
    val foreground: Boolean,
    val scan: RadioOpState,
    val advertise: RadioOpState,
    val scanDetail: String,
    val advertiseDetail: String,
    val blocks: List<DiscoveryBlock>,
    val lastError: String?,
    val peers: List<NearbyPeer>,
    val nowEpochMs: Long,
) {
    val isActivelyDiscovering: Boolean
        get() = wantedRunning && foreground &&
            (scan == RadioOpState.Running || advertise == RadioOpState.Running)
}
