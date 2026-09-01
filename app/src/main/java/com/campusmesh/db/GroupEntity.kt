package com.campusmesh.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "groups")
data class GroupEntity(
    @PrimaryKey
    val groupId: String,
    val groupName: String,
    val creatorNodeId: String,
    val createdAt: Long,
    val groupKeyBase64: String,
    val version: Long,
)
