package com.campusmesh.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

import kotlinx.coroutines.flow.Flow

@Dao
interface RelayPacketDao {
    @Query("SELECT * FROM relay_packets")
    fun observeAllPackets(): Flow<List<RelayPacketEntity>>

    @Query("SELECT * FROM relay_packets")
    suspend fun getAllPackets(): List<RelayPacketEntity>

    @Query("SELECT * FROM relay_packets WHERE destinationId = :peerId")
    suspend fun getPacketsForPeer(peerId: String): List<RelayPacketEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPacket(packet: RelayPacketEntity)

    @Query("DELETE FROM relay_packets WHERE packetId = :packetId")
    suspend fun deletePacket(packetId: String)

    @Query("DELETE FROM relay_packets WHERE expiresAt < :now")
    suspend fun deleteExpiredPackets(now: Long)
}
