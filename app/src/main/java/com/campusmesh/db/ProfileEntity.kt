package com.campusmesh.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "profiles")
data class ProfileEntity(
    @PrimaryKey
    val nodeId: String,
    val displayName: String,
    val avatarPath: String? = null,
    val version: Long = 1L,
    val contentHash: String? = null,
    val lastUpdated: Long = System.currentTimeMillis(),
)
