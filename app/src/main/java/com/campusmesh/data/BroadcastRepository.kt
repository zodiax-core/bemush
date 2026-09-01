package com.campusmesh.data

import com.campusmesh.db.BroadcastDao
import com.campusmesh.db.BroadcastEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BroadcastRepository @Inject constructor(
    private val broadcastDao: BroadcastDao,
) {
    val allBroadcasts: Flow<List<BroadcastEntity>> = broadcastDao.observeAllBroadcasts()

    fun getBroadcastsForChannel(channel: String): Flow<List<BroadcastEntity>> =
        broadcastDao.observeBroadcastsForChannel(channel)

    suspend fun saveBroadcast(
        broadcastId: String,
        channel: String,
        authorNodeId: String,
        content: String,
        signatureBase64: String,
        authorPublicKeyBase64: String,
        timestamp: Long,
        isVerified: Boolean,
    ) {
        val entity = BroadcastEntity(
            broadcastId = broadcastId,
            channel = channel,
            authorNodeId = authorNodeId,
            content = content,
            signatureBase64 = signatureBase64,
            authorPublicKeyBase64 = authorPublicKeyBase64,
            timestamp = timestamp,
            isVerified = isVerified,
        )
        broadcastDao.insertBroadcast(entity)
    }
}
