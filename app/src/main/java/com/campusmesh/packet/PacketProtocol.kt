package com.campusmesh.packet

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import timber.log.Timber

object PacketProtocol {
    private val json = Json { ignoreUnknownKeys = true }

    private const val MAX_TTL = 10
    private const val EXPIRY_MS = 3_600_000L // 1 hour

    fun serialize(packet: MeshPacket): String {
        return json.encodeToString(packet)
    }

    fun deserialize(data: String): MeshPacket? {
        return try {
            json.decodeFromString<MeshPacket>(data)
        } catch (e: Exception) {
            Timber.e(e, "Failed to deserialize mesh packet")
            null
        }
    }

    fun isValid(packet: MeshPacket): Boolean {
        return packet.protocolVersion == 1 &&
            packet.packetId.isNotBlank() &&
            packet.sourceId.isNotBlank() &&
            packet.destinationId.isNotBlank() &&
            packet.ttl > 0 &&
            packet.hopCount >= 0 &&
            packet.ttl <= MAX_TTL
    }

    fun isExpired(packet: MeshPacket, now: Long = System.currentTimeMillis()): Boolean {
        return (now - packet.timestamp) > EXPIRY_MS
    }
}
