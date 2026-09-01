package com.campusmesh.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface BroadcastDao {
    @Query("SELECT * FROM broadcasts ORDER BY timestamp DESC")
    fun observeAllBroadcasts(): Flow<List<BroadcastEntity>>

    @Query("SELECT * FROM broadcasts WHERE channel = :channel ORDER BY timestamp DESC")
    fun observeBroadcastsForChannel(channel: String): Flow<List<BroadcastEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBroadcast(broadcast: BroadcastEntity)

    @Query("DELETE FROM broadcasts WHERE broadcastId = :broadcastId")
    suspend fun deleteBroadcast(broadcastId: String)
}
