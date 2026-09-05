package com.campusmesh.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MessagingModelEnhancementTest {

    private fun getEffectiveChunkSize(mtu: Int): Int {
        return maxOf(20, minOf(mtu - 63, 440))
    }

    @Test
    fun chunkStringAlwaysFitsInsideAttMtuForStandardMtu247() {
        val mtu = 247
        val maxAttPayload = mtu - 3 // 244 bytes
        val chunkSize = getEffectiveChunkSize(mtu) // 184 bytes

        val packetId = UUID.randomUUID().toString() // 36 chars
        val i = 0
        val totalChunks = 5
        val slice = "A".repeat(chunkSize)

        val chunkStr = "PKT:$packetId:$i:$totalChunks:$slice"
        val chunkBytes = chunkStr.toByteArray(StandardCharsets.UTF_8)

        // Chunk bytes must strictly fit inside ATT payload
        assertTrue(
            "Chunk size ${chunkBytes.size} must be <= max ATT payload $maxAttPayload",
            chunkBytes.size <= maxAttPayload
        )
    }

    @Test
    fun chunkStringAlwaysFitsInsideAttMtuForMtu512() {
        val mtu = 512
        val maxAttPayload = mtu - 3 // 509 bytes
        val chunkSize = getEffectiveChunkSize(mtu) // 440 bytes

        val packetId = UUID.randomUUID().toString()
        val i = 9
        val totalChunks = 10
        val slice = "B".repeat(chunkSize)

        val chunkStr = "PKT:$packetId:$i:$totalChunks:$slice"
        val chunkBytes = chunkStr.toByteArray(StandardCharsets.UTF_8)

        assertTrue(
            "Chunk size ${chunkBytes.size} must be <= max ATT payload $maxAttPayload",
            chunkBytes.size <= maxAttPayload
        )
    }

    @Test
    fun chunkStringSafetyOnLowMtu() {
        val mtu = 100
        val maxAttPayload = mtu - 3 // 97 bytes
        val chunkSize = getEffectiveChunkSize(mtu) // 37 bytes

        val packetId = UUID.randomUUID().toString()
        val i = 0
        val totalChunks = 2
        val slice = "C".repeat(chunkSize)

        val chunkStr = "PKT:$packetId:$i:$totalChunks:$slice"
        val chunkBytes = chunkStr.toByteArray(StandardCharsets.UTF_8)

        assertTrue(
            "Chunk size ${chunkBytes.size} must be <= max ATT payload $maxAttPayload",
            chunkBytes.size <= maxAttPayload
        )
    }

    @Test
    fun outOfOrderChunkReassemblySucceedsWhenComplete() {
        val totalChunks = 3
        val buffer = ConcurrentHashMap<Int, String>()

        // Chunk 2 arrives first
        buffer[2] = "world!"
        assertFalse((0 until totalChunks).all { buffer.containsKey(it) })

        // Chunk 0 arrives second
        buffer[0] = "Hello "
        assertFalse((0 until totalChunks).all { buffer.containsKey(it) })

        // Chunk 1 arrives last
        buffer[1] = "beautiful "
        assertTrue((0 until totalChunks).all { buffer.containsKey(it) })

        val assembled = (0 until totalChunks).joinToString("") { buffer[it] ?: "" }
        assertEquals("Hello beautiful world!", assembled)
    }

    @Test
    fun deterministicInitiatorTieBreakingIsAsymmetric() {
        val nodeA = UUID.fromString("00000000-0000-0000-0000-000000000001").toString()
        val nodeB = UUID.fromString("00000000-0000-0000-0000-000000000002").toString()

        val aInitiates = nodeA > nodeB
        val bInitiates = nodeB > nodeA

        // One and only one node initiates immediately, preventing GATT 133 collision
        assertTrue(bInitiates)
        assertFalse(aInitiates)
        assertTrue(aInitiates != bInitiates)
    }
}
