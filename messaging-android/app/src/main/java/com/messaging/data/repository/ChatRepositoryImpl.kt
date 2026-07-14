package com.messaging.data.repository

import android.util.Log
import com.google.gson.Gson
import com.messaging.data.datasource.local.ChatDao
import com.messaging.data.datasource.local.ChatEntity
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import com.messaging.data.datasource.local.MessageDao
import com.messaging.data.datasource.remote.ChatApiService
import com.messaging.data.datasource.remote.WebSocketClient
import com.messaging.data.model.request.DeviceRegisterRequest
import com.messaging.data.model.WebSocketMessage
import com.messaging.data.model.request.MessageRequest
import com.messaging.data.model.response.ChatResponse
import com.messaging.data.model.response.MessageResponse
import com.messaging.data.model.response.UserResponse
import com.messaging.data.mapper.toDomain
import com.messaging.data.mapper.toEntity
import com.messaging.data.mapper.toEpochMillis
import com.messaging.domain.entity.Chat
import com.messaging.domain.entity.Message
import com.messaging.domain.entity.User
import com.messaging.domain.repository.ChatRepository
import com.messaging.util.TokenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ChatRepositoryImpl - Coordinates offline database caching and online real-time message exchange
 */
@Singleton
class ChatRepositoryImpl @Inject constructor(
    private val chatApiService: ChatApiService,
    private val chatDao: ChatDao,
    private val messageDao: MessageDao,
    private val webSocketClient: WebSocketClient,
    private val tokenManager: TokenManager,
    private val gson: Gson
) : ChatRepository {

    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    init {
        repositoryScope.launch {
            webSocketClient.incomingMessages.collect { wsMessage ->
                handleIncomingWebSocketMessage(wsMessage)
            }
        }
    }

    override fun getChatsFlow(): Flow<List<Chat>> {
        return chatDao.getAllChatsFlow().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override fun getMessagesFlow(chatId: Long): Flow<List<Message>> {
        return messageDao.getMessagesForChatFlow(chatId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun syncChats(): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            val privateResponse = chatApiService.getUserChats(page = 0, size = 100)
            val groupsResponse = chatApiService.getUserGroups(page = 0, size = 100)
            
            val privateEntities = if (privateResponse.isSuccessful && privateResponse.body() != null) {
                privateResponse.body()!!.map { it.toEntity() }
            } else {
                emptyList()
            }
            
            val groupEntities = if (groupsResponse.isSuccessful && groupsResponse.body() != null) {
                groupsResponse.body()!!.map { it.toEntity() }
            } else {
                emptyList()
            }
            
            val allEntities = privateEntities + groupEntities
            if (allEntities.isNotEmpty()) {
                chatDao.insertChats(allEntities)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun syncMessages(chatId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            if (chatId < 0) {
                return@withContext syncGroupMessages(-chatId)
            }
            val response = chatApiService.getChatMessages(chatId, page = 0, size = 100)
            if (response.isSuccessful && response.body() != null) {
                val messages = response.body()!!
                messageDao.insertMessages(messages.map { it.toEntity() })
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to sync messages: ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getOrCreateChat(recipientId: Long): Result<Chat> = withContext(Dispatchers.IO) {
        return@withContext try {
            val response = chatApiService.getOrCreateChat(recipientId)
            if (response.isSuccessful && response.body() != null) {
                val chatResponse = response.body()!!
                val entity = chatResponse.toEntity()
                chatDao.insertChat(entity)
                Result.success(entity.toDomain())
            } else {
                Result.failure(Exception("Failed to get or create chat: ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun sendMessage(
        recipientId: Long?,
        chatId: Long?,
        text: String,
        type: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            val isGroup = chatId != null && chatId < 0
            val actualGroupId = if (isGroup) -chatId!! else null
            val actualChatId = if (isGroup) null else chatId
            val actualRecipientId = if (isGroup) null else recipientId

            val wsRequest = MessageRequest(
                chatId = actualChatId,
                groupId = actualGroupId,
                recipientId = actualRecipientId,
                messageType = type,
                messageText = text
            )
            
            val wsMessage = WebSocketMessage(
                type = "CHAT_MESSAGE",
                recipientId = actualRecipientId,
                chatId = actualChatId,
                groupId = actualGroupId,
                payload = wsRequest
            )
            
            val sent = webSocketClient.sendMessage(wsMessage)
            if (sent) {
                Result.success(Unit)
            } else {
                if (isGroup) {
                    Result.failure(Exception("Failed to send group message: WebSocket disconnected"))
                } else {
                    val response = chatApiService.getOrCreateChat(recipientId ?: 0)
                    if (response.isSuccessful && response.body() != null) {
                        Result.success(Unit)
                    } else {
                        Result.failure(Exception("WebSocket is disconnected and fallback failed"))
                    }
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun markChatAsRead(chatId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            if (chatId < 0) {
                chatDao.resetUnreadCount(chatId)
                return@withContext Result.success(Unit)
            }
            chatDao.resetUnreadCount(chatId)
            val response = chatApiService.markChatAsRead(chatId)
            if (response.isSuccessful) {
                val chat = chatDao.getChatById(chatId)
                if (chat != null && chat.otherUserId != null) {
                    val wsMessage = WebSocketMessage(
                        type = "READ_RECEIPT",
                        chatId = chatId,
                        recipientId = chat.otherUserId
                    )
                    webSocketClient.sendMessage(wsMessage)
                }
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to mark chat as read on backend"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getContacts(): Result<List<User>> = withContext(Dispatchers.IO) {
        return@withContext try {
            val response = chatApiService.getAllUsers()
            if (response.isSuccessful && response.body() != null) {
                val users = response.body()!!.map { it.toDomain() }
                Result.success(users)
            } else {
                Result.failure(Exception("Failed to load contacts"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun searchContacts(query: String): Result<List<User>> = withContext(Dispatchers.IO) {
        return@withContext try {
            val response = chatApiService.searchUsers(query)
            if (response.isSuccessful && response.body() != null) {
                val users = response.body()!!.map { it.toDomain() }
                Result.success(users)
            } else {
                Result.failure(Exception("Failed to search contacts"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun connectWebSocket() {
        webSocketClient.connect()
    }

    override fun disconnectWebSocket() {
        webSocketClient.disconnect()
    }

    override fun observeWebSocketConnection(): Flow<Boolean> {
        return webSocketClient.connectionStatus
    }

    private suspend fun handleIncomingWebSocketMessage(wsMessage: WebSocketMessage) = withContext(Dispatchers.IO) {
        val currentUserId = tokenManager.userId.firstOrNull() ?: return@withContext
        
        try {
            when (wsMessage.type) {
                "CHAT_MESSAGE" -> {
                    val jsonStr = gson.toJson(wsMessage.payload)
                    val response = gson.fromJson(jsonStr, MessageResponse::class.java)
                    
                    val isOutgoing = response.senderId == currentUserId
                    val deliveryStatus = if (isOutgoing) "DELIVERED" else "READ"
                    
                    val entity = response.toEntity(deliveryStatus)
                    messageDao.insertMessage(entity)

                    val resolvedChatId = if (response.groupId != null) -response.groupId!! else response.chatId
                    if (resolvedChatId != null) {
                        var chat = chatDao.getChatById(resolvedChatId)
                        if (chat == null) {
                            if (response.groupId != null) {
                                val groupDetailsResp = chatApiService.getGroupDetails(response.groupId!!)
                                if (groupDetailsResp.isSuccessful && groupDetailsResp.body() != null) {
                                    val groupDetails = groupDetailsResp.body()!!
                                    chat = groupDetails.toEntity()
                                    chatDao.insertChat(chat)
                                } else {
                                    chat = ChatEntity(
                                        id = resolvedChatId,
                                        otherUserId = null,
                                        otherUsername = null,
                                        otherDisplayName = null,
                                        otherProfilePictureUrl = null,
                                        otherStatus = null,
                                        lastMessageText = response.messageText,
                                        lastMessageSenderId = response.senderId,
                                        lastMessageAt = response.createdAt.toEpochMillis(),
                                        isPinned = false,
                                        isArchived = false,
                                        unreadCount = 1,
                                        isGroup = true,
                                        groupName = "Group Chat",
                                        groupDescription = ""
                                    )
                                    chatDao.insertChat(chat)
                                }
                            } else {
                                syncChats()
                                chat = chatDao.getChatById(resolvedChatId)
                            }
                        }
                        
                        if (chat != null) {
                            val updatedChat = chat.copy(
                                lastMessageText = response.messageText,
                                lastMessageSenderId = response.senderId,
                                lastMessageAt = response.createdAt.toEpochMillis(),
                                unreadCount = if (isOutgoing) chat.unreadCount else chat.unreadCount + 1
                            )
                            chatDao.insertChat(updatedChat)
                        }
                    }
                }
                "DELIVERY_STATUS" -> {
                    val jsonStr = gson.toJson(wsMessage.payload)
                    val payloadMap = gson.fromJson(jsonStr, Map::class.java)
                    val messageId = (payloadMap["messageId"] as? Number)?.toLong()
                    val status = payloadMap["status"] as? String
                    if (messageId != null && status != null) {
                        messageDao.updateDeliveryStatus(messageId, status)
                    }
                }
                "READ_RECEIPT" -> {
                    val chatId = wsMessage.chatId
                    if (chatId != null) {
                        // Mark all messages as read locally
                    }
                }
            }
        } catch (e: Exception) {
            Log.e("ChatRepositoryImpl", "Error handling incoming WebSocket message", e)
        }
    }

    override suspend fun createGroup(name: String, description: String, memberIds: List<Long>): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = com.messaging.data.model.request.GroupCreateRequest(name, description, memberIds)
            val response = chatApiService.createGroup(request)
            if (response.isSuccessful) {
                syncChats()
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to create group: ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun uploadAttachment(fileBytes: ByteArray, fileName: String, mimeType: String): Result<Map<String, Any>> = withContext(Dispatchers.IO) {
        try {
            val mediaType = mimeType.toMediaTypeOrNull()
            val requestBody = fileBytes.toRequestBody(mediaType)
            val bodyPart = MultipartBody.Part.createFormData("file", fileName, requestBody)
            val response = chatApiService.uploadAttachment(bodyPart)
            val body = response.body()
            if (response.isSuccessful && body != null) {
                Result.success(body)
            } else {
                Result.failure(Exception("Upload failed: ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override fun getGroupMessagesFlow(groupId: Long): Flow<List<Message>> {
        return messageDao.getMessagesForGroupFlow(groupId).map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun syncGroupMessages(groupId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.getGroupMessages(groupId, page = 0, size = 100)
            val messages = response.body()
            if (response.isSuccessful && messages != null) {
                val entities = messages.map { it.toEntity() }
                messageDao.insertMessages(entities)
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to sync group messages: ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun editMessage(messageId: Long, newText: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.editMessage(messageId, newText)
            val body = response.body()
            if (response.isSuccessful && body != null) {
                messageDao.insertMessage(body.toEntity())
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to edit message: ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteMessage(messageId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.deleteMessage(messageId)
            val body = response.body()
            if (response.isSuccessful && body != null) {
                messageDao.insertMessage(body.toEntity())
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to delete message: ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun addReaction(messageId: Long, reaction: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.addReaction(messageId, reaction)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to add reaction: ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun removeReaction(messageId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.removeReaction(messageId)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to remove reaction: ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun togglePinChat(chatId: Long, pinned: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.pinChat(chatId, pinned)
            val body = response.body()
            if (response.isSuccessful && body != null) {
                chatDao.insertChat(body.toEntity())
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to pin chat: ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun toggleArchiveChat(chatId: Long, archived: Boolean): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.archiveChat(chatId, archived)
            val body = response.body()
            if (response.isSuccessful && body != null) {
                chatDao.insertChat(body.toEntity())
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to archive chat: ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun searchMessages(query: String): Result<List<Message>> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.searchMessages(query, page = 0, size = 50)
            val body = response.body()
            if (response.isSuccessful && body != null) {
                Result.success(body.map { it.toEntity().toDomain() })
            } else {
                Result.failure(Exception("Failed to search messages: ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun registerDevice(
        deviceId: String,
        deviceName: String?,
        deviceType: String?,
        osVersion: String?,
        appVersion: String?,
        fcmToken: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val request = DeviceRegisterRequest(
                deviceId = deviceId,
                deviceName = deviceName,
                deviceType = deviceType,
                osVersion = osVersion,
                appVersion = appVersion,
                fcmToken = fcmToken
            )
            val response = chatApiService.registerDevice(request)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to register device: ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun unregisterDevice(deviceId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val response = chatApiService.unregisterDevice(deviceId)
            if (response.isSuccessful) {
                Result.success(Unit)
            } else {
                Result.failure(Exception("Failed to unregister device: ${response.message()}"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
