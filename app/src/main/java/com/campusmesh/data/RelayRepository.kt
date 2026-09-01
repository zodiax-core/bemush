package com.campusmesh.data

import com.campusmesh.db.RelayPacketDao
import com.campusmesh.db.RelayPacketEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RelayRepository @Inject constructor(
    private val relayPacketDao: RelayPacketDao,
) {
    val allPackets: Flow<List<RelayPacketEntity>> = relayPacketDao.observeAllPackets()

    suspend fun storePacket(
        packetId: String,
        destinationId: String,
        payloadJson: String,
        ttl: Int,
        createdAt: Long,
        expiresAt: Long,
    ) {
        val entity = RelayPacketEntity(
            packetId = packetId,
            destinationId = destinationId,
            payloadJson = payloadJson,
            ttl = ttl,
            createdAt = createdAt,
            expiresAt = expiresAt,
        )
        relayPacketDao.insertPacket(entity)
    }

    suspend fun getAllPackets(): List<RelayPacketEntity> {
        return relayPacketDao.getAllPackets()
    }

    suspend fun getPacketsForPeer(peerId: String): List<RelayPacketEntity> {
        return relayPacketDao.getPacketsForPeer(peerId)
    }

    suspend fun removePacket(packetId: String) {
        relayPacketDao.deletePacket(packetId)
    }

    suspend fun cleanupExpired(now: Long) {
        relayPacketDao.deleteExpiredPackets(now)
    }
}
