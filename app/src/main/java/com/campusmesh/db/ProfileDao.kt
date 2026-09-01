package com.campusmesh.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles WHERE nodeId = :nodeId")
    suspend fun getProfile(nodeId: String): ProfileEntity?

    @Query("SELECT * FROM profiles WHERE nodeId = :nodeId")
    fun observeProfile(nodeId: String): Flow<ProfileEntity?>

    @Query("SELECT * FROM profiles")
    fun observeAllProfiles(): Flow<List<ProfileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: ProfileEntity)
}
