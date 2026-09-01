package com.campusmesh.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "broadcasts")
data class BroadcastEntity(
    @PrimaryKey
    val broadcastId: String,
    val channel: String, // e.g., "campus_announcements", "emergency"
    val authorNodeId: String,
    val content: String,
    val signatureBase64: String,
    val authorPublicKeyBase64: String,
    val timestamp: Long,
    val isVerified: Boolean,
)
