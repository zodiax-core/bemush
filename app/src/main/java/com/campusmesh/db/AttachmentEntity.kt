package com.campusmesh.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "attachments")
data class AttachmentEntity(
    @PrimaryKey
    val fileId: String,
    val fileName: String,
    val fileSize: Long,
    val mimeType: String,
    val localPath: String,
    val checksumSha256: String,
    val transferStatus: String, // PENDING, TRANSFERRING, COMPLETED, FAILED
    val totalChunks: Int,
    val receivedChunks: Int,
    val priority: Int, // 1: Emergency, 2: Text, 3: Profile, 4: Image, 5: Large file
    val timestamp: Long,
)
