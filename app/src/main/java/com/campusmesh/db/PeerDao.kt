package com.campusmesh.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface PeerDao {
    @Query("SELECT * FROM peers ORDER BY lastSeenEpochMs DESC")
    fun getAllPeers(): Flow<List<PeerEntity>>

    @Query("SELECT * FROM peers WHERE nodeId = :nodeId")
    suspend fun getPeer(nodeId: String): PeerEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPeer(peer: PeerEntity)

    @Query("DELETE FROM peers WHERE lastSeenEpochMs < :cutoffTime")
    suspend fun deleteStalePeers(cutoffTime: Long)

    @Query("DELETE FROM peers")
    suspend fun deleteAllPeers()
}
