package com.campusmesh.broadcast

import com.campusmesh.data.BroadcastRepository
import com.campusmesh.db.BroadcastDao
import com.campusmesh.db.BroadcastEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BroadcastTest {

    @Test
    fun testBroadcastRepositorySaveAndRetrieve() = runBlocking {
        val dao = FakeBroadcastDao()
        val repo = BroadcastRepository(dao)

        repo.saveBroadcast(
            broadcastId = "bcast_123",
            channel = "campus_announcements",
            authorNodeId = "node_1",
            content = "Welcome to CampusMesh!",
            signatureBase64 = "dummy_sig",
            authorPublicKeyBase64 = "dummy_pub",
            timestamp = System.currentTimeMillis(),
            isVerified = true,
        )

        val broadcasts = runBlocking { dao.observeAllBroadcasts().collect { list -> assertEquals(1, list.size); assertEquals("Welcome to CampusMesh!", list.first().content) } }
    }

    private class FakeBroadcastDao : BroadcastDao {
        private val list = mutableListOf<BroadcastEntity>()

        override fun observeAllBroadcasts(): Flow<List<BroadcastEntity>> = flowOf(list.toList())

        override fun observeBroadcastsForChannel(channel: String): Flow<List<BroadcastEntity>> =
            flowOf(list.filter { it.channel == channel })

        override suspend fun insertBroadcast(broadcast: BroadcastEntity) {
            list.add(broadcast)
        }

        override suspend fun deleteBroadcast(broadcastId: String) {
            list.removeIf { it.broadcastId == broadcastId }
        }
    }
}
