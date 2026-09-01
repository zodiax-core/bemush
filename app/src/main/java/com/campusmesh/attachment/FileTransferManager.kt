package com.campusmesh.attachment

import android.content.Context
import com.campusmesh.data.AttachmentRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

enum class TransferPriority(val level: Int) {
    EMERGENCY(1),
    TEXT(2),
    PROFILE(3),
    IMAGE(4),
    LARGE_FILE(5),
}

@Singleton
class FileTransferManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val attachmentRepository: AttachmentRepository,
) {
    suspend fun prepareOutgoingFile(
        sourceFile: File,
        mimeType: String,
        priority: TransferPriority,
    ): FileTransferSession? = withContext(Dispatchers.IO) {
        if (!sourceFile.exists()) return@withContext null

        val fileId = UUID.randomUUID().toString()
        val checksum = computeChecksum(sourceFile)
        val fileSize = sourceFile.length()
        val chunkSize = 4096 // 4KB per chunk fitting MTU
        val totalChunks = ((fileSize + chunkSize - 1) / chunkSize).toInt()

        attachmentRepository.upsertAttachment(
            fileId = fileId,
            fileName = sourceFile.name,
            fileSize = fileSize,
            mimeType = mimeType,
            localPath = sourceFile.absolutePath,
            checksumSha256 = checksum,
            transferStatus = "PENDING",
            totalChunks = totalChunks,
            receivedChunks = 0,
            priority = priority.level,
            timestamp = System.currentTimeMillis(),
        )

        FileTransferSession(
            fileId = fileId,
            fileName = sourceFile.name,
            fileSize = fileSize,
            checksumSha256 = checksum,
            chunkSize = chunkSize,
            totalChunks = totalChunks,
            priority = priority,
        )
    }

    private fun computeChecksum(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read = input.read(buffer)
            while (read != -1) {
                digest.update(buffer, 0, read)
                read = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

data class FileTransferSession(
    val fileId: String,
    val fileName: String,
    val fileSize: Long,
    val checksumSha256: String,
    val chunkSize: Int,
    val totalChunks: Int,
    val priority: TransferPriority,
)
