package com.campusmesh.data

import com.campusmesh.db.AttachmentDao
import com.campusmesh.db.AttachmentEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AttachmentRepository @Inject constructor(
    private val attachmentDao: AttachmentDao,
) {
    val allAttachments: Flow<List<AttachmentEntity>> = attachmentDao.observeAllAttachments()

    suspend fun getAttachment(fileId: String): AttachmentEntity? = attachmentDao.getAttachment(fileId)

    suspend fun upsertAttachment(
        fileId: String,
        fileName: String,
        fileSize: Long,
        mimeType: String,
        localPath: String,
        checksumSha256: String,
        transferStatus: String,
        totalChunks: Int,
        receivedChunks: Int,
        priority: Int,
        timestamp: Long,
    ) {
        val entity = AttachmentEntity(
            fileId = fileId,
            fileName = fileName,
            fileSize = fileSize,
            mimeType = mimeType,
            localPath = localPath,
            checksumSha256 = checksumSha256,
            transferStatus = transferStatus,
            totalChunks = totalChunks,
            receivedChunks = receivedChunks,
            priority = priority,
            timestamp = timestamp,
        )
        attachmentDao.upsertAttachment(entity)
    }

    suspend fun removeAttachment(fileId: String) {
        attachmentDao.deleteAttachment(fileId)
    }
}
