package com.campusmesh.data

import com.campusmesh.db.ProfileDao
import com.campusmesh.db.ProfileEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepository @Inject constructor(
    private val profileDao: ProfileDao,
) {
    fun observeProfile(nodeId: String): Flow<ProfileEntity?> = profileDao.observeProfile(nodeId)
    val allProfiles: Flow<List<ProfileEntity>> = profileDao.observeAllProfiles()

    suspend fun getProfile(nodeId: String): ProfileEntity? = profileDao.getProfile(nodeId)

    suspend fun upsertProfile(
        nodeId: String,
        displayName: String,
        avatarPath: String?,
        version: Long,
        contentHash: String?,
    ) {
        val entity = ProfileEntity(
            nodeId = nodeId,
            displayName = displayName,
            avatarPath = avatarPath,
            version = version,
            contentHash = contentHash,
            lastUpdated = System.currentTimeMillis(),
        )
        profileDao.upsertProfile(entity)
    }
}
