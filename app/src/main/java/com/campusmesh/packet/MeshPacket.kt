package com.campusmesh.packet

import kotlinx.serialization.Serializable

@Serializable
data class MeshPacket(
    val protocolVersion: Int,
    val packetId: String,
    val messageId: String,
    val sourceId: String,
    val destinationId: String,
    val timestamp: Long,
    val ttl: Int,
    val hopCount: Int,
    val payload: String,
)
