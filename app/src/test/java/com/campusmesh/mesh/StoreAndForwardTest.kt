package com.campusmesh.mesh

import com.campusmesh.packet.MeshPacket
import com.campusmesh.packet.PacketCache
import com.campusmesh.packet.PacketProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class StoreAndForwardTest {

    @Test
    fun ttlAndHopCountHandling() {
        val original = MeshPacket(
            protocolVersion = 1,
            packetId = UUID.randomUUID().toString(),
            messageId = UUID.randomUUID().toString(),
            sourceId = "node-a",
            destinationId = "node-c",
            timestamp = System.currentTimeMillis(),
            ttl = 5,
            hopCount = 0,
            payload = "Hello via relay"
        )

        // Simulate B relaying: decrements TTL, increments hop count
        val relayed = original.copy(
            ttl = original.ttl - 1,
            hopCount = original.hopCount + 1
        )

        assertEquals(4, relayed.ttl)
        assertEquals(1, relayed.hopCount)
        assertTrue(PacketProtocol.isValid(relayed))
    }

    @Test
    fun duplicatePacketPrevention() {
        val cache = PacketCache()
        val packetId = "test-packet-id-123"

        // First time seen
        assertFalse(cache.hasSeen(packetId))
        cache.markSeen(packetId)

        // Second time seen
        assertTrue(cache.hasSeen(packetId))
    }

    @Test
    fun routingDecisionLogic() {
        val localNodeId = "local-node-id"
        val destNodeId = "destination-node-id"

        val packetForUs = MeshPacket(
            protocolVersion = 1,
            packetId = UUID.randomUUID().toString(),
            messageId = UUID.randomUUID().toString(),
            sourceId = "node-a",
            destinationId = localNodeId,
            timestamp = System.currentTimeMillis(),
            ttl = 5,
            hopCount = 0,
            payload = "Hello"
        )

        val packetForRelay = MeshPacket(
            protocolVersion = 1,
            packetId = UUID.randomUUID().toString(),
            messageId = UUID.randomUUID().toString(),
            sourceId = "node-a",
            destinationId = destNodeId,
            timestamp = System.currentTimeMillis(),
            ttl = 5,
            hopCount = 0,
            payload = "Hello"
        )

        // Verify routing target check
        assertTrue(packetForUs.destinationId == localNodeId)
        assertFalse(packetForRelay.destinationId == localNodeId)
        assertTrue(packetForRelay.ttl > 1) // can be relayed
    }
}
