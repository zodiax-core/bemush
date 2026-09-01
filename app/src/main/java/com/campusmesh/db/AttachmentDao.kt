package com.campusmesh.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AttachmentDao {
    @Query("SELECT * FROM attachments WHERE fileId = :fileId")
    suspend fun getAttachment(fileId: String): AttachmentEntity?

    @Query("SELECT * FROM attachments")
    fun observeAllAttachments(): Flow<List<AttachmentEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAttachment(attachment: AttachmentEntity)

    @Query("DELETE FROM attachments WHERE fileId = :fileId")
    suspend fun deleteAttachment(fileId: String)
}
