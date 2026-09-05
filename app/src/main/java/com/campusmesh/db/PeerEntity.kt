package com.campusmesh.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "peers")
data class PeerEntity(
    @PrimaryKey
    val nodeId: String,
    val deviceAddress: String,
    val rssiDbm: Int,
    val lastSeenEpochMs: Long,
    val publicKeyBase64: String? = null,
    /** Human-readable display name as sent by the peer during key exchange. */
    val displayName: String? = null,
    /** Custom nickname / alias set locally by the user. */
    val customName: String? = null,
    /** Local filepath to peer's cached profile avatar image. */
    val avatarPath: String? = null,
    /** SHA-256 content hash of the avatar image for peer sync check. */
    val avatarHash: String? = null,
)
