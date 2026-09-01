package com.campusmesh.data

import com.campusmesh.db.MessageDao
import com.campusmesh.db.MessageEntity
import com.campusmesh.db.UnreadCount
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MessageRepository @Inject constructor(
    private val messageDao: MessageDao,
) {
    val allMessages: Flow<List<MessageEntity>> = messageDao.getAllMessages()

    val latestMessagePerConversation: Flow<List<MessageEntity>> =
        messageDao.getLatestMessagePerConversation()

    val unreadCountsPerConversation: Flow<List<UnreadCount>> =
        messageDao.getUnreadCountsPerConversation()

    fun getMessagesForConversation(peerId: String): Flow<List<MessageEntity>> =
        messageDao.getMessagesForConversation(peerId)

    suspend fun saveMessage(
        messageId: String,
        senderId: String,
        recipientId: String,
        content: String,
        timestamp: Long,
        status: String,
        isRead: Boolean = false,
    ) {
        val entity = MessageEntity(
            messageId = messageId,
            senderId = senderId,
            recipientId = recipientId,
            content = content,
            timestamp = timestamp,
            status = status,
            isRead = isRead,
        )
        messageDao.insertMessage(entity)
    }

    suspend fun updateMessageStatus(messageId: String, status: String) {
        messageDao.updateMessageStatus(messageId, status)
    }

    suspend fun markConversationAsRead(peerId: String) {
        messageDao.markConversationAsRead(peerId)
    }

    suspend fun updateAllOutgoingStatusForPeer(peerId: String, status: String) {
        messageDao.updateAllOutgoingStatusForPeer(peerId, status)
    }

    suspend fun clearAll() {
        messageDao.deleteAllMessages()
    }
}
