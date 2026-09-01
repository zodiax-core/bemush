package com.campusmesh.profile

import com.campusmesh.data.ProfileRepository
import com.campusmesh.db.ProfileDao
import com.campusmesh.db.ProfileEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ProfileTest {

    @Test
    fun testProfileRepositoryUpsertAndGet() = runBlocking {
        val dao = FakeProfileDao()
        val repo = ProfileRepository(dao)

        repo.upsertProfile(
            nodeId = "node_123",
            displayName = "Alice",
            avatarPath = null,
            version = 1L,
            contentHash = "hash_abc"
        )

        val profile = repo.getProfile("node_123")
        assertNotNull(profile)
        assertEquals("Alice", profile?.displayName)
        assertEquals(1L, profile?.version)
        assertEquals("hash_abc", profile?.contentHash)
    }

    private class FakeProfileDao : ProfileDao {
        private val map = mutableMapOf<String, ProfileEntity>()

        override suspend fun getProfile(nodeId: String): ProfileEntity? = map[nodeId]

        override fun observeProfile(nodeId: String): Flow<ProfileEntity?> = flowOf(map[nodeId])

        override fun observeAllProfiles(): Flow<List<ProfileEntity>> = flowOf(map.values.toList())

        override suspend fun upsertProfile(profile: ProfileEntity) {
            map[profile.nodeId] = profile
        }
    }
}
