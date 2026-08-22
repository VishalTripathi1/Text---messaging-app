package com.messaging.data.repository

import android.util.Log
import com.google.gson.Gson
import com.messaging.data.datasource.local.ChatDao
import com.messaging.data.datasource.local.ChatEntity
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
import com.messaging.data.datasource.local.MessageDao
import com.messaging.data.datasource.local.MessageEntity
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
            entities.map { entity ->
                val lastText = if (!entity.lastMessageText.isNullOrBlank()) {
                    entity.lastMessageText
                } else {
                    val latestMsg = if (entity.isGroup) {
                        messageDao.getLatestMessageForGroup(-entity.id)
                    } else {
                        messageDao.getLatestMessageForChat(entity.id)
                    }
                    latestMsg?.messageText
                }
                
                val lastTime = entity.lastMessageAt ?: run {
                    val latestMsg = if (entity.isGroup) {
                        messageDao.getLatestMessageForGroup(-entity.id)
                    } else {
                        messageDao.getLatestMessageForChat(entity.id)
                    }
                    latestMsg?.createdAt
                }

                entity.copy(
                    lastMessageText = lastText,
                    lastMessageAt = lastTime
                ).toDomain()
            }
        }.flowOn(Dispatchers.IO)
    }

    override fun getMessagesFlow(chatId: Long): Flow<List<Message>> {
        return messageDao.getMessagesForChatFlow(chatId).map { entities ->
            entities.map { it.toDomain() }
        }.flowOn(Dispatchers.IO)
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
                groupsResponse.body()!!.map { groupResp ->
                    val existing = chatDao.getChatById(-groupResp.id)
                    ChatEntity(
                        id = -groupResp.id,
                        otherUserId = null,
                        otherUsername = null,
                        otherDisplayName = null,
                        otherProfilePictureUrl = null,
                        otherStatus = null,
                        lastMessageText = existing?.lastMessageText,
                        lastMessageSenderId = existing?.lastMessageSenderId,
                        lastMessageStatus = existing?.lastMessageStatus,
                        lastMessageAt = existing?.lastMessageAt ?: groupResp.createdAt.toEpochMillis(),
                        isPinned = existing?.isPinned ?: false,
                        isArchived = existing?.isArchived ?: false,
                        unreadCount = existing?.unreadCount ?: 0,
                        isGroup = true,
                        groupName = groupResp.groupName,
                        groupDescription = groupResp.description
                    )
                }
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
                Result.failure(Exception(parseErrorMessage(response, "Failed to sync messages")))
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
                Result.failure(Exception(parseErrorMessage(response, "Failed to get or create chat")))
            }
        } catch (e: Exception) {
            // Anything thrown before the isSuccessful check above (a network timeout, a DNS
            // failure, a malformed JSON body that fails to deserialize, etc.) was previously
            // returned as-is via Result.failure(e) with no logging at all - if the exception's
            // own message happened to be uninformative (or come from a library with a generic
            // default message), there was no way to tell what actually went wrong afterward.
            // Logging it here means the next time this happens, logcat will show the real cause.
            Log.e("ChatRepositoryImpl", "getOrCreateChat failed for recipientId=$recipientId", e)
            Result.failure(Exception("Failed to start chat: ${e.message ?: e.javaClass.simpleName}", e))
        }
    }

    override suspend fun sendMessage(
        recipientId: Long?,
        chatId: Long?,
        text: String,
        type: String,
        attachmentPath: String?,
        attachmentFileName: String?,
        attachmentFileType: String?,
        attachmentFileSize: Long?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        return@withContext try {
            val isGroup = chatId != null && chatId < 0
            val actualGroupId = if (isGroup) -chatId!! else null
            val actualChatId = if (isGroup) null else chatId
            val actualRecipientId = if (isGroup) null else recipientId

            val currentUserId = tokenManager.userId.firstOrNull() ?: 0L
            val currentUsername = tokenManager.username.firstOrNull() ?: ""
            val tempMessageId = -System.currentTimeMillis()

            val tempEntity = MessageEntity(
                id = tempMessageId,
                chatId = actualChatId,
                groupId = actualGroupId,
                senderId = currentUserId,
                senderUsername = currentUsername,
                messageText = text,
                messageType = type,
                isEdited = false,
                isDeleted = false,
                createdAt = System.currentTimeMillis(),
                deliveryStatus = "PENDING",
                attachmentPath = attachmentPath,
                attachmentFileName = attachmentFileName,
                attachmentFileType = attachmentFileType,
                attachmentFileSize = attachmentFileSize
            )
            messageDao.insertMessage(tempEntity)

            val resolvedChatId = if (actualGroupId != null) -actualGroupId else actualChatId
            if (resolvedChatId != null) {
                val chat = chatDao.getChatById(resolvedChatId)
                if (chat != null) {
                    val previewText = if (text.isBlank() && attachmentPath != null) {
                        when (type) {
                            "IMAGE" -> "📷 Photo"
                            "VIDEO" -> "🎥 Video"
                            "VOICE" -> "🎤 Voice message"
                            else -> "📎 Document"
                        }
                    } else text
                    chatDao.insertChat(chat.copy(
                        lastMessageText = previewText,
                        lastMessageSenderId = currentUserId,
                        lastMessageStatus = "PENDING",
                        lastMessageAt = System.currentTimeMillis()
                    ))
                }
            }

            val wsRequest = MessageRequest(
                chatId = actualChatId,
                groupId = actualGroupId,
                recipientId = actualRecipientId,
                messageType = type,
                messageText = text,
                attachmentPath = attachmentPath,
                attachmentFileName = attachmentFileName,
                attachmentFileType = attachmentFileType,
                attachmentFileSize = attachmentFileSize
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
                // webSocketClient.sendMessage() returning true only means the frame was handed
                // to OkHttp's outgoing queue - it does NOT guarantee the server received or
                // processed it (a dropped connection, a flaky network, or a backend error after
                // this point are all invisible at this layer). Previously this just declared
                // success and relied entirely on a WS echo-back to clear the "pending" (clock)
                // message - if that echo never arrived, the message stayed stuck as pending
                // forever with no automatic retry. Now we wait briefly for that echo, and if
                // it doesn't show up in time we fall back to the HTTP path (below), which gives
                // a definite DELIVERED or FAILED outcome instead of an indefinite pending state.
                val confirmed = withTimeoutOrNull(6000) {
                    while (messageDao.getMessageById(tempMessageId) != null) {
                        delay(500)
                    }
                    true
                } ?: false

                if (confirmed) {
                    return@withContext Result.success(Unit)
                }
                // Fall through to the HTTP fallback below for a guaranteed resolution.
            }

            run {
                val httpResp = chatApiService.sendHttpMessage(wsRequest)
                if (httpResp.isSuccessful && httpResp.body() != null) {
                    val messageResp = httpResp.body()!!
                    messageDao.deleteMessageById(tempMessageId)
                    val entity = messageResp.toEntity("DELIVERED")
                    messageDao.insertMessage(entity)
                    
                    val resolvedChatId = if (actualGroupId != null) -actualGroupId else actualChatId
                    if (resolvedChatId != null) {
                        val chat = chatDao.getChatById(resolvedChatId)
                        if (chat != null) {
                            chatDao.insertChat(chat.copy(
                                lastMessageText = messageResp.messageText,
                                lastMessageSenderId = messageResp.senderId,
                                lastMessageAt = messageResp.createdAt.toEpochMillis()
                            ))
                        }
                    }
                    Result.success(Unit)
                } else {
                    messageDao.updateDeliveryStatus(tempMessageId, "FAILED")
                    Result.failure(Exception(parseErrorMessage(httpResp, "Failed to send message")))
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
                Result.failure(Exception(parseErrorMessage(response, "Failed to mark chat as read")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun getContacts(): Result<List<User>> = withContext(Dispatchers.IO) {
        return@withContext try {
            val response = chatApiService.getAllUsers()
            if (response.isSuccessful && response.body() != null) {
                val currentUserId = tokenManager.userId.firstOrNull() ?: 0L
                val users = response.body()!!
                    .filter { it.id != currentUserId }
                    .map { it.toDomain() }
                Result.success(users)
            } else {
                Result.failure(Exception(parseErrorMessage(response, "Failed to load contacts")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun searchContacts(query: String): Result<List<User>> = withContext(Dispatchers.IO) {
        return@withContext try {
            val response = chatApiService.searchUsers(query)
            if (response.isSuccessful && response.body() != null) {
                val currentUserId = tokenManager.userId.firstOrNull() ?: 0L
                val users = response.body()!!
                    .filter { it.id != currentUserId }
                    .map { it.toDomain() }
                Result.success(users)
            } else {
                Result.failure(Exception(parseErrorMessage(response, "Failed to search contacts")))
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
                "CHAT_MESSAGE", "MESSAGE_UPDATE", "REACTION_UPDATE" -> {
                    val jsonStr = gson.toJson(wsMessage.payload)
                    val response = gson.fromJson(jsonStr, MessageResponse::class.java)
                    
                    val isOutgoing = response.senderId == currentUserId
                    val deliveryStatus = if (isOutgoing) "SENT" else "READ"
                    
                    val groupId = response.groupId
                    if (isOutgoing && wsMessage.type == "CHAT_MESSAGE") {
                        if (groupId != null) {
                            messageDao.deletePendingMessagesForGroup(groupId)
                        } else if (response.chatId != null) {
                            messageDao.deletePendingMessagesForChat(response.chatId)
                        }
                    }

                    // Message and reaction updates use dedicated event types. REPLACE-on-insert
                    // applies them locally without turning an edit of an old message into a new
                    // chat-list preview or unread notification.
                    val isGenuinelyNewMessage = messageDao.getMessageById(response.id) == null

                    val entity = response.toEntity(deliveryStatus)
                    messageDao.insertMessage(entity)

                    val resolvedChatId = if (groupId != null) -groupId else response.chatId
                    if (resolvedChatId != null) {
                        var chat = chatDao.getChatById(resolvedChatId)
                        if (chat == null) {
                            if (groupId != null) {
                                val groupDetailsResp = chatApiService.getGroupDetails(groupId)
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
                                        lastMessageStatus = response.deliveryStatus,
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
                        
                        if (chat != null && isGenuinelyNewMessage) {
                            if (isOutgoing) {
                                chatDao.updateLastMessageOnly(
                                    chatId = resolvedChatId,
                                    text = response.messageText,
                                    senderId = response.senderId,
                                    at = response.createdAt.toEpochMillis()
                                )
                            } else {
                                // Atomic SQL increment - see ChatDao for why this replaced the
                                // old read-then-write-whole-row approach.
                                chatDao.incrementUnreadAndUpdateLastMessage(
                                    chatId = resolvedChatId,
                                    text = response.messageText,
                                    senderId = response.senderId,
                                    at = response.createdAt.toEpochMillis()
                                )
                            }
                        }
                    }
                }
                "USER_STATUS" -> {
                    val jsonStr = gson.toJson(wsMessage.payload)
                    val payloadMap = gson.fromJson(jsonStr, Map::class.java)
                    val userId = (payloadMap["userId"] as? Number)?.toLong()
                    val status = payloadMap["status"] as? String
                    if (userId != null && status != null) {
                        chatDao.updateOtherUserStatus(userId, status)
                    }
                }
                "DELIVERY_STATUS" -> {
                    val jsonStr = gson.toJson(wsMessage.payload)
                    val payloadMap = gson.fromJson(jsonStr, Map::class.java)
                    val messageId = (payloadMap["messageId"] as? Number)?.toLong()
                    val status = payloadMap["status"] as? String
                    if (messageId != null && status != null) {
                        val existingMessage = messageDao.getMessageById(messageId)
                        if (existingMessage != null) {
                            // We already hold this message under its real id (e.g. it was
                            // already echoed back via CHAT_MESSAGE) - just advance its
                            // status: PENDING -> SENT -> DELIVERED -> READ.
                            messageDao.updateDeliveryStatus(messageId, status)
                            val conversationId = existingMessage.groupId?.let { -it } ?: existingMessage.chatId
                            if (conversationId != null) {
                                chatDao.updateLastMessageStatus(
                                    conversationId,
                                    existingMessage.senderId,
                                    existingMessage.messageText,
                                    status
                                )
                            }
                        } else {
                            // This is the first confirmation for a message we just sent.
                            // Locally it is still stored under its temporary negative id
                            // (see sendMessage()), so it can't be found by the real,
                            // server-assigned messageId yet. Resolve it using the
                            // envelope-level chatId/groupId (same fields READ_RECEIPT
                            // already relies on) so the pending/clock icon can clear.
                            val envelopeGroupId = wsMessage.groupId
                            val envelopeChatId = wsMessage.chatId
                            val pending = when {
                                envelopeGroupId != null -> messageDao.getOldestPendingMessageForGroup(envelopeGroupId)
                                envelopeChatId != null -> messageDao.getOldestPendingMessageForChat(envelopeChatId)
                                else -> null
                            }
                            if (pending != null) {
                                messageDao.deleteMessageById(pending.id)
                                messageDao.insertMessage(pending.copy(id = messageId, deliveryStatus = status))
                            }
                        }
                    }
                }
                "READ_RECEIPT" -> {
                    val chatId = wsMessage.chatId
                    if (chatId != null) {
                        messageDao.markChatMessagesAsRead(chatId)
                        val latest = messageDao.getLatestMessageForChat(chatId)
                        if (latest != null && latest.senderId == currentUserId) {
                            chatDao.updateLastMessageStatus(chatId, latest.senderId, latest.messageText, "READ")
                        }
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
            if (response.isSuccessful && response.body() != null) {
                val groupResponse = response.body()!!
                val entity = groupResponse.toEntity()
                chatDao.insertChat(entity)
                syncChats()
                Result.success(Unit)
            } else {
                Result.failure(Exception(parseErrorMessage(response, "Failed to create group")))
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
                Result.failure(Exception(parseErrorMessage(response, "Upload failed")))
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
                Result.failure(Exception(parseErrorMessage(response, "Failed to sync group messages")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun editMessage(messageId: Long, newText: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            messageDao.updateMessageText(messageId, newText)
            val msgEntity = messageDao.getMessageById(messageId)
            if (msgEntity?.chatId != null) {
                val chat = chatDao.getChatById(msgEntity.chatId)
                if (chat != null) {
                    chatDao.insertChat(chat.copy(lastMessageText = newText))
                }
            }
            if (messageId > 0) {
                val response = chatApiService.editMessage(messageId, newText)
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    messageDao.insertMessage(body.toEntity())
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteMessage(messageId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            messageDao.markMessageAsDeleted(messageId)
            val msgEntity = messageDao.getMessageById(messageId)
            if (msgEntity?.chatId != null) {
                val chat = chatDao.getChatById(msgEntity.chatId)
                if (chat != null) {
                    chatDao.insertChat(chat.copy(lastMessageText = "This message was deleted"))
                }
            }
            if (messageId > 0) {
                val response = chatApiService.deleteMessage(messageId)
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    messageDao.insertMessage(body.toEntity())
                }
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun addReaction(messageId: Long, reaction: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val currentUserId = tokenManager.userId.firstOrNull() ?: 0L
            val currentUsername = tokenManager.username.firstOrNull() ?: ""
            val existingEntity = messageDao.getMessageById(messageId)
            if (existingEntity != null) {
                val currentReactions = existingEntity.reactions?.filterNot { it.userId == currentUserId } ?: emptyList()
                val newReactionObj = com.messaging.domain.entity.Reaction(
                    userId = currentUserId,
                    username = currentUsername,
                    reaction = reaction
                )
                val updatedList = currentReactions + newReactionObj
                messageDao.insertMessage(existingEntity.copy(reactions = updatedList))
            }
            if (messageId > 0) {
                chatApiService.addReaction(messageId, reaction)
            }
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun removeReaction(messageId: Long): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val currentUserId = tokenManager.userId.firstOrNull() ?: 0L
            val existingEntity = messageDao.getMessageById(messageId)
            if (existingEntity != null && existingEntity.reactions != null) {
                val updatedList = existingEntity.reactions.filterNot { it.userId == currentUserId }
                messageDao.insertMessage(existingEntity.copy(reactions = updatedList))
            }
            if (messageId > 0) {
                chatApiService.removeReaction(messageId)
            }
            Result.success(Unit)
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
                Result.failure(Exception(parseErrorMessage(response, "Failed to pin chat")))
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
                Result.failure(Exception(parseErrorMessage(response, "Failed to archive chat")))
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
                Result.failure(Exception(parseErrorMessage(response, "Failed to search messages")))
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
                Result.failure(Exception(parseErrorMessage(response, "Failed to register device")))
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
                Result.failure(Exception(parseErrorMessage(response, "Failed to unregister device")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun toggleReaction(messageId: Long, reaction: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val currentUserId = tokenManager.userId.firstOrNull() ?: 0L
            val existingEntity = messageDao.getMessageById(messageId)
            val userReaction = existingEntity?.reactions?.firstOrNull { it.userId == currentUserId }
            if (userReaction != null && userReaction.reaction == reaction) {
                removeReaction(messageId)
            } else {
                addReaction(messageId, reaction)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun clearLocalData(): Unit = withContext(Dispatchers.IO) {
        try {
            chatDao.deleteAllChats()
            messageDao.deleteAllMessages()
        } catch (e: Exception) {
            Log.e("ChatRepositoryImpl", "Error clearing local data", e)
        }
    }

    /**
     * Builds a useful error message from a failed HTTP response.
     *
     * `response.message()` is the raw HTTP status *reason phrase* - it is frequently blank
     * (Render, like most modern hosts, negotiates HTTP/2, which drops reason phrases entirely
     * per spec) and was never the actual error the backend reported anyway. Every failure path
     * in this repository was building its message from that field, which is why errors like
     * "Failed to get or create chat: " showed up with nothing useful after the colon. This
     * reads the actual JSON error body the backend sends (see GlobalExceptionHandler on the
     * server) and falls back to the HTTP status code if the body can't be parsed.
     */
    private fun parseErrorMessage(response: retrofit2.Response<*>, fallback: String): String {
        val bodyText = try {
            response.errorBody()?.string()
        } catch (e: Exception) {
            null
        }
        if (!bodyText.isNullOrBlank()) {
            try {
                val json = org.json.JSONObject(bodyText)
                if (json.has("message")) {
                    return json.getString("message")
                }
            } catch (e: Exception) {
                // Not JSON - fall through to the generic fallback below.
            }
        }
        return "$fallback (HTTP ${response.code()})"
    }
}
