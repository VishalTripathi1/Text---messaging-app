package com.messaging.domain.repository

import com.messaging.domain.entity.Chat
import com.messaging.domain.entity.Message
import com.messaging.domain.entity.User
import kotlinx.coroutines.flow.Flow

/**
 * ChatRepository - Domain-level interface for chat, message, contact, and WebSocket services
 */
interface ChatRepository {
    fun getChatsFlow(): Flow<List<Chat>>
    
    fun getMessagesFlow(chatId: Long): Flow<List<Message>>
    
    suspend fun syncChats(): Result<Unit>
    
    suspend fun syncMessages(chatId: Long): Result<Unit>
    
    suspend fun getOrCreateChat(recipientId: Long): Result<Chat>
    
    suspend fun sendMessage(
        recipientId: Long?,
        chatId: Long?,
        text: String,
        type: String,
        attachmentPath: String? = null,
        attachmentFileName: String? = null,
        attachmentFileType: String? = null,
        attachmentFileSize: Long? = null
    ): Result<Unit>
    
    suspend fun markChatAsRead(chatId: Long): Result<Unit>
    
    suspend fun getContacts(): Result<List<User>>
    
    suspend fun searchContacts(query: String): Result<List<User>>
    
    fun connectWebSocket()
    
    fun disconnectWebSocket()
    
    fun observeWebSocketConnection(): Flow<Boolean>

    // Group & Media support
    suspend fun createGroup(name: String, description: String, memberIds: List<Long>): Result<Unit>
    
    suspend fun uploadAttachment(fileBytes: ByteArray, fileName: String, mimeType: String): Result<Map<String, Any>>
    
    fun getGroupMessagesFlow(groupId: Long): Flow<List<Message>>
    
    suspend fun syncGroupMessages(groupId: Long): Result<Unit>
    suspend fun getGroupDetails(groupId: Long): Result<com.messaging.data.model.response.GroupResponse>
    suspend fun updateGroup(groupId: Long, groupName: String?, description: String?): Result<com.messaging.data.model.response.GroupResponse>
    suspend fun addGroupMembers(groupId: Long, userIds: List<Long>): Result<Unit>
    suspend fun removeGroupMember(groupId: Long, userId: Long): Result<Unit>

    suspend fun editMessage(messageId: Long, newText: String): Result<Unit>

    suspend fun deleteMessage(messageId: Long): Result<Unit>

    suspend fun addReaction(messageId: Long, reaction: String): Result<Unit>

    suspend fun removeReaction(messageId: Long): Result<Unit>

    suspend fun blockUser(userId: Long): Result<Unit>

    suspend fun unblockUser(userId: Long): Result<Unit>

    suspend fun isUserBlocked(userId: Long): Result<Boolean>

    suspend fun togglePinChat(chatId: Long, pinned: Boolean): Result<Unit>

    suspend fun toggleArchiveChat(chatId: Long, archived: Boolean): Result<Unit>

    suspend fun clearChat(chatId: Long): Result<Unit>

    suspend fun deleteChat(chatId: Long): Result<Unit>

    suspend fun searchMessages(query: String): Result<List<Message>>

    suspend fun registerDevice(
        deviceId: String,
        deviceName: String?,
        deviceType: String?,
        osVersion: String?,
        appVersion: String?,
        fcmToken: String
    ): Result<Unit>

    suspend fun unregisterDevice(deviceId: String): Result<Unit>

    suspend fun toggleReaction(messageId: Long, reaction: String): Result<Unit>

    suspend fun clearLocalData()
}
