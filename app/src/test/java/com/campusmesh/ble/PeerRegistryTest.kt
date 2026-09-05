package com.campusmesh.ble

import com.campusmesh.data.PeerRepository
import com.campusmesh.db.PeerDao
import com.campusmesh.db.PeerEntity
import com.campusmesh.platform.EpochClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class PeerRegistryTest {

    private val peerDao = FakePeerDao()
    private val peerRepository = PeerRepository(peerDao)

    @Test
    fun ignoresLocalNode() {
        val clock = MutableClock(1_000L)
        val registry = PeerRegistry(clock, peerRepository)
        val local = UUID.randomUUID()

        registry.upsert(local, deviceAddress = "AA:BB:CC:DD:EE:FF", rssiDbm = -40, localNodeId = local)

        assertTrue(registry.peers.value.isEmpty())
    }

    @Test
    fun updatesRssiAndLastSeenForSamePeer() {
        val clock = MutableClock(1_000L)
        val registry = PeerRegistry(clock, peerRepository)
        val local = UUID.randomUUID()
        val peer = UUID.randomUUID()

        registry.upsert(peer, deviceAddress = "AA:BB:CC:DD:EE:FF", rssiDbm = -90, localNodeId = local)
        clock.now = 1_500L
        registry.upsert(peer, deviceAddress = "AA:BB:CC:DD:EE:FF", rssiDbm = -55, localNodeId = local)

        val stored = registry.peers.value.single()
        assertEquals(peer, stored.nodeId)
        assertEquals("AA:BB:CC:DD:EE:FF", stored.deviceAddress)
        assertEquals(-55, stored.rssiDbm)
        assertEquals(1_000L, stored.firstSeenEpochMs)
        assertEquals(1_500L, stored.lastSeenEpochMs)
    }

    @Test
    fun prunesStalePeers() {
        val clock = MutableClock(10_000L)
        val registry = PeerRegistry(clock, peerRepository)
        val local = UUID.randomUUID()
        val stale = UUID.randomUUID()
        val fresh = UUID.randomUUID()

        registry.upsert(stale, deviceAddress = "11:22:33:44:55:66", rssiDbm = -70, localNodeId = local, nowMs = 1_000L)
        registry.upsert(fresh, deviceAddress = "77:88:99:AA:BB:CC", rssiDbm = -60, localNodeId = local, nowMs = 9_000L)
        registry.pruneStale(staleAfterMs = 5_000L, nowMs = 10_000L)

        assertEquals(listOf(fresh), registry.peers.value.map { it.nodeId })
    }

    @Test
    fun clearRemovesAllPeers() {
        val registry = PeerRegistry(MutableClock(1L), peerRepository)
        registry.upsert(UUID.randomUUID(), "AA:BB:CC:DD:EE:FF", -50, UUID.randomUUID())
        registry.clear()
        assertTrue(registry.peers.value.isEmpty())
    }

    private class MutableClock(var now: Long) : EpochClock {
        override fun nowMillis(): Long = now
    }

    private class FakePeerDao : PeerDao {
        override fun getAllPeers(): Flow<List<PeerEntity>> = flowOf(emptyList())
        override suspend fun getPeer(nodeId: String): PeerEntity? = null
        override suspend fun upsertPeer(peer: PeerEntity) {}
        override suspend fun updateCustomName(nodeId: String, customName: String?) {}
        override suspend fun deleteStalePeers(cutoffTime: Long) {}
        override suspend fun deleteAllPeers() {}
    }
}
