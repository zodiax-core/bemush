package com.campusmesh.data

import com.campusmesh.db.PeerDao
import com.campusmesh.db.PeerEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PeerRepository @Inject constructor(
    private val peerDao: PeerDao,
) {
    val allPeers: Flow<List<PeerEntity>> = peerDao.getAllPeers()

    suspend fun getPeer(nodeId: String): PeerEntity? {
        return peerDao.getPeer(nodeId)
    }

    suspend fun upsertPeer(
        nodeId: String,
        deviceAddress: String,
        rssiDbm: Int,
        lastSeenEpochMs: Long,
        publicKeyBase64: String? = null,
        displayName: String? = null,
        customName: String? = null,
        avatarPath: String? = null,
        avatarHash: String? = null,
    ) {
        val existing = peerDao.getPeer(nodeId)
        val entity = PeerEntity(
            nodeId = nodeId,
            deviceAddress = deviceAddress,
            rssiDbm = rssiDbm,
            lastSeenEpochMs = lastSeenEpochMs,
            publicKeyBase64 = publicKeyBase64 ?: existing?.publicKeyBase64,
            displayName = displayName ?: existing?.displayName,
            customName = customName ?: existing?.customName,
            avatarPath = avatarPath ?: existing?.avatarPath,
            avatarHash = avatarHash ?: existing?.avatarHash,
        )
        peerDao.upsertPeer(entity)
    }

    suspend fun setCustomName(nodeId: String, customName: String?) {
        val cleanName = customName?.trim()?.ifBlank { null }
        val existing = peerDao.getPeer(nodeId)
        if (existing != null) {
            peerDao.upsertPeer(existing.copy(customName = cleanName))
        } else {
            val entity = PeerEntity(
                nodeId = nodeId,
                deviceAddress = "",
                rssiDbm = -100,
                lastSeenEpochMs = System.currentTimeMillis(),
                customName = cleanName,
            )
            peerDao.upsertPeer(entity)
        }
    }

    suspend fun pruneStale(cutoffTime: Long) {
        peerDao.deleteStalePeers(cutoffTime)
    }

    suspend fun clearAll() {
        peerDao.deleteAllPeers()
    }
}
