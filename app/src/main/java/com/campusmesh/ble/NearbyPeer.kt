package com.campusmesh.ble

import java.util.UUID

data class NearbyPeer(
    val nodeId: UUID,
    val deviceAddress: String,
    val rssiDbm: Int,
    val lastSeenEpochMs: Long,
    val firstSeenEpochMs: Long,
) {
    val shortLabel: String get() = AdvertisePayload.shortLabel(nodeId)
}
