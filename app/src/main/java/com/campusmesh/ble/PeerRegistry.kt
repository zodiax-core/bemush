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

    private val lastDbWriteMs = HashMap<UUID, Long>()

    fun upsert(
        nodeId: UUID,
        deviceAddress: String,
        rssiDbm: Int,
        localNodeId: UUID,
        staleAfterMs: Long = DEFAULT_STALE_AFTER_MS,
        nowMs: Long = clock.nowMillis(),
    ) {
        if (nodeId == localNodeId) return
        var shouldPersist = false
        synchronized(lock) {
            val existing = byId[nodeId]
            byId[nodeId] = NearbyPeer(
                nodeId = nodeId,
                deviceAddress = deviceAddress,
                rssiDbm = rssiDbm,
                lastSeenEpochMs = nowMs,
                firstSeenEpochMs = existing?.firstSeenEpochMs ?: nowMs,
            )
            val lastWrite = lastDbWriteMs[nodeId] ?: 0L
            if (nowMs - lastWrite >= DB_THROTTLE_MS) {
                lastDbWriteMs[nodeId] = nowMs
                shouldPersist = true
            }
            pruneLocked(nowMs, staleAfterMs)
            publishLocked()
        }
        if (shouldPersist) {
            scope.launch {
                peerRepository.upsertPeer(
                    nodeId = nodeId.toString(),
                    deviceAddress = deviceAddress,
                    rssiDbm = rssiDbm,
                    lastSeenEpochMs = nowMs
                )
            }
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
        // NOTE: We deliberately do NOT delete peers from SQLite here.
        // Room peer records contain persistent contact data (display names, custom aliases,
        // avatars, public keys) which must survive when peers temporarily step out of range.
    }

    fun clear() {
        synchronized(lock) {
            byId.clear()
            publishLocked()
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
        // Sort by most-recently-seen only. No RSSI filter — every discovered peer
        // appears immediately regardless of signal strength.
        _peers.value = byId.values.sortedByDescending { it.lastSeenEpochMs }
    }

    companion object {
        // 45s window: reduces peer flicker during BLE scan gaps and temporary signal drops.
        const val DEFAULT_STALE_AFTER_MS: Long = 45_000L
        const val DB_THROTTLE_MS: Long = 5_000L
    }
}
