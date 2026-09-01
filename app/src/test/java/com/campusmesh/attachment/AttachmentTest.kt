package com.campusmesh.attachment

import com.campusmesh.data.AttachmentRepository
import com.campusmesh.db.AttachmentDao
import com.campusmesh.db.AttachmentEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class AttachmentTest {

    @Test
    fun testAttachmentRepositoryUpsertAndGet() = runBlocking {
        val dao = FakeAttachmentDao()
        val repo = AttachmentRepository(dao)

        repo.upsertAttachment(
            fileId = "file_123",
            fileName = "photo.jpg",
            fileSize = 1024L,
            mimeType = "image/jpeg",
            localPath = "/tmp/photo.jpg",
            checksumSha256 = "dummy_checksum",
            transferStatus = "COMPLETED",
            totalChunks = 10,
            receivedChunks = 10,
            priority = TransferPriority.IMAGE.level,
            timestamp = System.currentTimeMillis()
        )

        val attachment = repo.getAttachment("file_123")
        assertNotNull(attachment)
        assertEquals("photo.jpg", attachment?.fileName)
        assertEquals(1024L, attachment?.fileSize)
        assertEquals(TransferPriority.IMAGE.level, attachment?.priority)
    }

    private class FakeAttachmentDao : AttachmentDao {
        private val map = mutableMapOf<String, AttachmentEntity>()

        override suspend fun getAttachment(fileId: String): AttachmentEntity? = map[fileId]

        override fun observeAllAttachments(): Flow<List<AttachmentEntity>> = flowOf(map.values.toList())

        override suspend fun upsertAttachment(attachment: AttachmentEntity) {
            map[attachment.fileId] = attachment
        }

        override suspend fun deleteAttachment(fileId: String) {
            map.remove(fileId)
        }
    }
}
