package com.campusmesh.ble

import com.campusmesh.data.PeerRepository
import com.campusmesh.platform.EpochClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PeerRegistry @Inject constructor(
    private val clock: EpochClock,
    private val peerRepository: PeerRepository,
) {
    private val lock = Any()
    private val byId = LinkedHashMap<UUID, NearbyPeer>()
    private val _peers = MutableStateFlow<List<NearbyPeer>>(emptyList())
    val peers: StateFlow<List<NearbyPeer>> = _peers.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun upsert(
        nodeId: UUID,
        deviceAddress: String,
        rssiDbm: Int,
        localNodeId: UUID,
        staleAfterMs: Long = DEFAULT_STALE_AFTER_MS,
        nowMs: Long = clock.nowMillis(),
    ) {
        if (nodeId == localNodeId) return
        synchronized(lock) {
            val existing = byId[nodeId]
            byId[nodeId] = NearbyPeer(
                nodeId = nodeId,
                deviceAddress = deviceAddress,
                rssiDbm = rssiDbm,
                lastSeenEpochMs = nowMs,
                firstSeenEpochMs = existing?.firstSeenEpochMs ?: nowMs,
            )
            pruneLocked(nowMs, staleAfterMs)
            publishLocked()
        }
        scope.launch {
            peerRepository.upsertPeer(
                nodeId = nodeId.toString(),
                deviceAddress = deviceAddress,
                rssiDbm = rssiDbm,
                lastSeenEpochMs = nowMs
            )
        }
    }

    fun pruneStale(
        staleAfterMs: Long = DEFAULT_STALE_AFTER_MS,
        nowMs: Long = clock.nowMillis(),
    ) {
        synchronized(lock) {
            pruneLocked(nowMs, staleAfterMs)
            publishLocked()
        }
        scope.launch {
            peerRepository.pruneStale(nowMs - staleAfterMs)
        }
    }

    fun clear() {
        synchronized(lock) {
            byId.clear()
            publishLocked()
        }
        scope.launch {
            peerRepository.clearAll()
        }
    }

    private fun pruneLocked(nowMs: Long, staleAfterMs: Long) {
        val iterator = byId.entries.iterator()
        while (iterator.hasNext()) {
            val peer = iterator.next().value
            if (nowMs - peer.lastSeenEpochMs > staleAfterMs) {
                iterator.remove()
            }
        }
    }

    private fun publishLocked() {
        _peers.value = byId.values.sortedWith(
            compareByDescending<NearbyPeer> { it.lastSeenEpochMs }
                .thenByDescending { it.rssiDbm },
        )
    }

    companion object {
        const val DEFAULT_STALE_AFTER_MS: Long = 20_000L
    }
}
