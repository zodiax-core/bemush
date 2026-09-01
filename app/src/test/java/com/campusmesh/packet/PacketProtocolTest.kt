package com.campusmesh.packet

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class PacketProtocolTest {

    @Test
    fun serializeAndDeserialize() {
        val original = MeshPacket(
            protocolVersion = 1,
            packetId = UUID.randomUUID().toString(),
            messageId = UUID.randomUUID().toString(),
            sourceId = "node-a",
            destinationId = "node-c",
            timestamp = System.currentTimeMillis(),
            ttl = 5,
            hopCount = 0,
            payload = "Hello mesh!",
        )

        val serialized = PacketProtocol.serialize(original)
        val deserialized = PacketProtocol.deserialize(serialized)

        assertEquals(original, deserialized)
    }

    @Test
    fun validationFailsInvalidPackets() {
        val invalid = MeshPacket(
            protocolVersion = 99, // wrong version
            packetId = "", // empty ID
            messageId = "msg1",
            sourceId = "src",
            destinationId = "dst",
            timestamp = 12345,
            ttl = 0, // invalid ttl
            hopCount = 0,
            payload = "data",
        )

        assertFalse(PacketProtocol.isValid(invalid))
    }

    @Test
    fun packetCacheDetectsDuplicates() {
        val cache = PacketCache()
        val id = "pkt-123"

        assertFalse(cache.hasSeen(id))
        cache.markSeen(id)
        assertTrue(cache.hasSeen(id))
    }

    @Test
    fun expiredPacketDetection() {
        val oldTimestamp = System.currentTimeMillis() - 7_200_000L // 2 hours ago
        val packet = MeshPacket(
            protocolVersion = 1,
            packetId = "pkt-old",
            messageId = "msg-old",
            sourceId = "src",
            destinationId = "dst",
            timestamp = oldTimestamp,
            ttl = 5,
            hopCount = 0,
            payload = "old data",
        )

        assertTrue(PacketProtocol.isExpired(packet))
    }

    @Test
    fun corruptedPacketDeserializationReturnsNull() {
        val corrupted = "{ invalid json data }"
        val result = PacketProtocol.deserialize(corrupted)
        assertNull(result)
    }
}
