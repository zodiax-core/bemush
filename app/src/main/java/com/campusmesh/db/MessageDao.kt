package com.campusmesh.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

data class UnreadCount(
    val peerId: String,
    val unreadCount: Int,
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY timestamp DESC")
    fun getAllMessages(): Flow<List<MessageEntity>>

    /**
     * All messages that belong to a conversation with [peerId].
     * A conversation message is one where [peerId] is either the sender or the recipient.
     */
    @Query("SELECT * FROM messages WHERE senderId = :peerId OR recipientId = :peerId ORDER BY timestamp ASC")
    fun getMessagesForConversation(peerId: String): Flow<List<MessageEntity>>

    /**
     * One row per unique remote peer (conversation partner), ordered by most-recent message.
     */
    @Query("""
        SELECT * FROM messages
        WHERE messageId IN (
            SELECT messageId FROM (
                SELECT messageId, MAX(timestamp) as maxTs,
                    CASE WHEN senderId = 'local' THEN recipientId ELSE senderId END as peerId
                FROM messages
                GROUP BY CASE WHEN senderId = 'local' THEN recipientId ELSE senderId END
            )
        )
        ORDER BY timestamp DESC
    """)
    fun getLatestMessagePerConversation(): Flow<List<MessageEntity>>

    @Query("""
        SELECT senderId as peerId, COUNT(*) as unreadCount
        FROM messages
        WHERE senderId != 'local' AND isRead = 0
        GROUP BY senderId
    """)
    fun getUnreadCountsPerConversation(): Flow<List<UnreadCount>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Query("UPDATE messages SET status = :status WHERE messageId = :messageId")
    suspend fun updateMessageStatus(messageId: String, status: String)

    @Query("UPDATE messages SET isRead = 1 WHERE senderId = :peerId AND isRead = 0")
    suspend fun markConversationAsRead(peerId: String)

    @Query("UPDATE messages SET status = :status WHERE recipientId = :peerId AND senderId = 'local'")
    suspend fun updateAllOutgoingStatusForPeer(peerId: String, status: String)

    @Query("DELETE FROM messages")
    suspend fun deleteAllMessages()
}
