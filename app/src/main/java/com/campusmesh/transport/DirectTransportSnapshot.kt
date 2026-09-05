package com.campusmesh.transport

import com.campusmesh.db.MessageEntity
import com.campusmesh.db.RelayPacketEntity

data class DirectTransportSnapshot(
    val connectionState: TransportConnectionState = TransportConnectionState.Disconnected,
    val peerLabel: String? = null,
    val peerAddress: String? = null,
    val peerNodeId: String? = null,
    val bytesSent: Long = 0L,
    val bytesReceived: Long = 0L,
    val lastReceivedMessage: String? = null,
    val lastError: String? = null,
    val logs: List<String> = emptyList(),
    val persistedMessages: List<MessageEntity> = emptyList(),
    val relayPackets: List<RelayPacketEntity> = emptyList(),
    val connectedPeerNodeIds: Set<String> = emptySet(),
    val connectedDeviceAddresses: Set<String> = emptySet(),
    val packetsForwarded: Long = 0L,
    val duplicatesDiscarded: Long = 0L,
    val averageHopCount: Float = 0f,
    val averageDeliveryTimeMs: Long = 0L,
    val expiredCount: Long = 0L,
) {
    fun isPeerDirectlyConnected(nodeId: String?, address: String?): Boolean {
        if (!nodeId.isNullOrBlank() && connectedPeerNodeIds.contains(nodeId)) return true
        if (!address.isNullOrBlank() && connectedDeviceAddresses.contains(address)) return true
        return false
    }
}

enum class TransportConnectionState {
    Disconnected,
    Connecting,
    Connected,
    Failed,
}
