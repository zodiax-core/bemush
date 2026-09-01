package com.campusmesh.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "relay_packets")
data class RelayPacketEntity(
    @PrimaryKey
    val packetId: String,
    val destinationId: String,
    val payloadJson: String,
    val ttl: Int,
    val createdAt: Long,
    val expiresAt: Long,
)
