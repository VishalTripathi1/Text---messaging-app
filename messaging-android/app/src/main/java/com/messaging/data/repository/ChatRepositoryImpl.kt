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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
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

    /** Guards flushUnsentMessages() so overlapping reconnects cannot send the same row twice. */
    private val isFlushingUnsent = AtomicBoolean(false)

    /** Guards prefetchRecentConversations() so overlapping reconnects cannot fetch the same history twice. */
    private val isPrefetching = AtomicBoolean(false)

    /** How many of the most recent conversations prefetchRecentConversations() will warm up. */
    private val prefetchConversationLimit = 10

    /** Half-width of the window used to recognise an already-stored message - see flushUnsentMessages(). */
    private val duplicateWindowMillis = 120_000L

    /**
     * How far along the send has got. Higher is later; -1 for anything unrecognised so an unknown
     * value can never win against a known one.
     */
    private fun statusRank(status: String?): Int = when (status?.uppercase()) {
        "FAILED" -> 0
        "PENDING" -> 1
        "SENT" -> 2
        "DELIVERED" -> 3
        "READ" -> 4
        else -> -1
    }

    /**
     * Returns [row] with whichever status is further along - the one it carries, or the one already
     * stored locally for the same id.
     *
     * Delivery status has several independent writers (the POST response, the DELIVERY_STATUS and
     * CHAT_MESSAGE frames, READ_RECEIPT, and every sync) and they all persist through
     * `insertMessage`, which is REPLACE-on-conflict: whoever writes last overwrites the whole row
     * including its status. That is what pinned a delivered message on one tick. The recipient's
     * socket takes the message *while the server is still assembling the POST response*, so the
     * DELIVERY_STATUS frame usually lands first and correctly writes DELIVERED - and then the HTTP
     * response arrives carrying the status as it was before the broadcast, "SENT", and clobbers it.
     * Nothing re-sends DELIVERY_STATUS for a message that is already delivered
     * (MessageServiceImpl.markMessageAsDelivered only advances PENDING rows), so the tick stayed
     * wrong for good.
     *
     * Delivery only ever moves forward, so the fix is to make these writes idempotent with respect
     * to ordering rather than to try to order them.
     */
    private fun keepBestStatus(row: MessageEntity): MessageEntity {
        val stored = messageDao.getDeliveryStatus(row.id) ?: return row
        return if (statusRank(stored) > statusRank(row.deliveryStatus)) {
            row.copy(deliveryStatus = stored)
        } else {
            row
        }
    }

    init {
        repositoryScope.launch {
            webSocketClient.incomingMessages.collect { wsMessage ->
                handleIncomingWebSocketMessage(wsMessage)
            }
        }

        // Repair whatever failed while the server was unreachable, the moment it becomes reachable
        // again.
        //
        // This is what removes the need to log in again. The backend sleeps on Render's free tier
        // and takes most of a minute to boot, so the first sync after any idle period used to fail
        // and simply be discarded - neither ChatListViewModel.syncChats() nor ChatListScreen's
        // ON_RESUME looks at the Result. Nothing retried, so the chat list stayed stale and sends
        // stayed stuck until a fresh login happened to rebuild everything from scratch.
        //
        // A successful WebSocket handshake is the one unambiguous signal that the server is awake
        // and this device is authenticated, so it is the right trigger. Living in the repository
        // rather than in a ViewModel means it fires no matter which screen is showing, and survives
        // the screen that started the connection being disposed.
        repositoryScope.launch {
            webSocketClient.connectionStatus
                // No distinctUntilChanged(): connectionStatus is a StateFlow, which already drops
                // repeated equal values, so a false -> true -> false -> true sequence delivers
                // exactly one true per genuine reconnect.
                .filter { it }
                .collect {
                    Log.d("ChatRepositoryImpl", "WebSocket connected - resyncing, flushing unsent messages and warming conversation history")
                    syncChats()
                    flushUnsentMessages()
                    prefetchRecentConversations()
                }
        }
    }

    /**
     * Pulls history for recent conversations that have none cached yet.
     *
     * Opening a conversation renders straight from Room, so the second visit is instant - it is the
     * first one that has to wait for the server, and on a free-tier instance that wait is what makes
     * the app feel like it is loading old messages from scratch. Doing it here, off the back of a
     * successful connection, spends that wait while the user is still looking at the chat list.
     *
     * Deliberately only for conversations with nothing cached: a conversation that already has
     * messages is redrawn immediately from the cache and refreshed by its own screen, so re-fetching
     * it here would cost bandwidth to change nothing. That also makes this a one-off per conversation
     * rather than something that repeats on every reconnect. Sequential rather than parallel, and
     * capped, to stay well clear of starving a foreground request that the user is actually waiting on.
     */
    private suspend fun prefetchRecentConversations() {
        if (!isPrefetching.compareAndSet(false, true)) return
        try {
            for (chat in chatDao.getRecentChats(prefetchConversationLimit)) {
                val alreadyCached = if (chat.isGroup) {
                    messageDao.getLatestMessageForGroup(-chat.id) != null
                } else {
                    messageDao.getLatestMessageForChat(chat.id) != null
                }
                if (alreadyCached) continue
                // Negative ids are groups; syncMessages routes them to syncGroupMessages itself.
                syncMessages(chat.id)
            }
        } catch (e: Exception) {
            // Prefetching is opportunistic - a failure here must not affect anything else.
            Log.d("ChatRepositoryImpl", "Conversation prefetch stopped: ${e.message}")
        } finally {
            isPrefetching.set(false)
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

    /**
     * Refreshes the cached chat list from the server.
     *
     * Two behaviours here are load-bearing for the "app can't reach the server until I log in again"
     * problem:
     *
     * 1. It now reports failure. Previously an unsuccessful response was coerced to an empty list
     *    and still returned Result.success, so no caller could distinguish "you have no chats" from
     *    "the request died against a sleeping server" - and therefore nothing ever retried.
     *
     * 2. It prunes. It used to only insert, so a chat deleted server-side lingered locally forever
     *    as a ghost row reading "No messages yet".
     *
     * The prune is keyed strictly on the HTTP status, never on the mapped list being empty: a 500
     * from the server would otherwise wipe the user's entire cached chat list. Private chats and
     * groups are pruned from their own responses only, since a successful private-chat fetch says
     * nothing about which groups still exist.
     */
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
                        otherBio = null,
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

            if (privateResponse.isSuccessful) {
                val serverPrivateIds = privateEntities.map { it.id }
                if (serverPrivateIds.isEmpty()) {
                    chatDao.deleteAllStalePrivateChats()
                } else {
                    chatDao.deleteStalePrivateChatsNotIn(serverPrivateIds)
                }
            }

            if (groupsResponse.isSuccessful) {
                val serverGroupIds = groupEntities.map { it.id }
                if (serverGroupIds.isEmpty()) {
                    chatDao.deleteAllStaleGroupChats()
                } else {
                    chatDao.deleteStaleGroupChatsNotIn(serverGroupIds)
                }
            }

            if (!privateResponse.isSuccessful) {
                Result.failure(Exception(parseErrorMessage(privateResponse, "Failed to sync chats")))
            } else if (!groupsResponse.isSuccessful) {
                Result.failure(Exception(parseErrorMessage(groupsResponse, "Failed to sync groups")))
            } else {
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Log.e("ChatRepositoryImpl", "syncChats failed", e)
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
                messageDao.insertMessages(
                    messages.map { keepBestStatus(it.toEntity(it.deliveryStatus ?: "SENT")) }
                )
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
        // Declared outside the try so the catch below can reach it. It could not before, which is
        // the whole reason a timed-out send sat on a PENDING clock icon forever: only the non-2xx
        // branch marked the row FAILED, and a timeout throws rather than returning a response.
        var tempMessageId: Long? = null
        return@withContext try {
            val isGroup = chatId != null && chatId < 0
            val actualGroupId = if (isGroup) -chatId!! else null
            val actualChatId = if (isGroup) null else chatId
            val actualRecipientId = if (isGroup) null else recipientId

            val currentUserId = tokenManager.userId.firstOrNull() ?: 0L
            val currentUsername = tokenManager.username.firstOrNull() ?: ""
            val newTempMessageId = -System.currentTimeMillis()
            tempMessageId = newTempMessageId

            val tempEntity = MessageEntity(
                id = newTempMessageId,
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
            
            // Persist through HTTP so the caller receives a definitive server response. A
            // successful WebSocket send only means OkHttp queued a frame; it does not prove the
            // server stored it, and waiting for an echo left senders permanently on PENDING when
            // that echo was lost. The backend broadcasts this saved message to recipients.
            val httpResp = chatApiService.sendHttpMessage(wsRequest)
            if (httpResp.isSuccessful && httpResp.body() != null) {
                val messageResp = httpResp.body()!!
                messageDao.deleteMessageById(newTempMessageId)
                // keepBestStatus, not the response's status outright: the DELIVERY_STATUS frame for
                // this very message is usually applied before this line runs, and this response is
                // the older of the two answers. See keepBestStatus().
                val stored = keepBestStatus(
                    messageResp.toEntity(messageResp.deliveryStatus ?: "SENT")
                )
                messageDao.insertMessage(stored)

                val confirmedChatId = if (actualGroupId != null) -actualGroupId else actualChatId
                if (confirmedChatId != null) {
                    val chat = chatDao.getChatById(confirmedChatId)
                    if (chat != null) {
                        chatDao.insertChat(chat.copy(
                            lastMessageText = messageResp.messageText,
                            lastMessageSenderId = messageResp.senderId,
                            lastMessageStatus = stored.deliveryStatus,
                            lastMessageAt = messageResp.createdAt.toEpochMillis()
                        ))
                    }
                }
                Result.success(Unit)
            } else {
                markSendFailed(newTempMessageId)
                Result.failure(Exception(parseErrorMessage(httpResp, "Failed to send message")))
            }
        } catch (e: Exception) {
            // A timeout, a dropped connection or a malformed body all land here. Before, the temp
            // row was simply abandoned on PENDING, so the message showed a clock icon that never
            // resolved and there was nothing to tell flushUnsentMessages() to retry it.
            Log.e("ChatRepositoryImpl", "sendMessage failed for chatId=$chatId", e)
            tempMessageId?.let { markSendFailed(it) }
            Result.failure(e)
        }
    }

    /**
     * Marks a locally-created message as failed, on the message row and on the chat-list preview.
     *
     * The preview has to be updated separately because the chats table caches its own copy of the
     * last message's status; leaving it on PENDING would keep a clock icon on the chat list even
     * once the conversation itself showed the send as failed.
     */
    private fun markSendFailed(tempMessageId: Long) {
        messageDao.updateDeliveryStatus(tempMessageId, "FAILED")

        val message = messageDao.getMessageById(tempMessageId) ?: return
        val resolvedChatId = message.groupId?.let { -it } ?: message.chatId ?: return
        chatDao.updateLastMessageStatus(
            chatId = resolvedChatId,
            senderId = message.senderId,
            text = message.messageText,
            status = "FAILED"
        )
    }

    /**
     * Re-sends locally-created messages that never reached the server. Called from init the moment
     * the WebSocket connects, which is the first reliable proof the backend is awake again.
     *
     * Without this, a message typed while the server was still booting stayed on the device forever:
     * the send threw, the row kept its negative local id, and nothing ever tried again short of the
     * user noticing and retyping it.
     *
     * Duplicate risk, stated plainly: if the original POST did reach the server and only the
     * response was lost, resending stores the message twice. The endpoint has no idempotency key -
     * adding one needs a client-generated id column and a unique index on the backend - so this is
     * mitigated, not eliminated. Every affected conversation is re-synced first, and a row is
     * skipped when the server already holds a message from the same sender with the same text
     * within [duplicateWindowMillis] of it. The window is wide because the two timestamps come from
     * different clocks: the device's for the local row, the server's for the synced one.
     */
    private suspend fun flushUnsentMessages(): Unit = withContext(Dispatchers.IO) {
        // Two reconnects in quick succession would otherwise both read the same rows and send each
        // of them twice.
        if (!isFlushingUnsent.compareAndSet(false, true)) {
            Log.d("ChatRepositoryImpl", "flushUnsentMessages already in progress - skipping")
            return@withContext
        }

        try {
            val unsent = messageDao.getUnsentMessages()
            if (unsent.isEmpty()) return@withContext

            Log.i("ChatRepositoryImpl", "Flushing ${unsent.size} unsent message(s)")

            // Pull the server's copy of every affected conversation first, so the duplicate check
            // below has something to compare against.
            unsent
                .mapNotNull { row -> row.groupId?.let { -it } ?: row.chatId }
                .distinct()
                .forEach { syncMessages(it) }

            for (row in unsent) {
                if (row.chatId == null && row.groupId == null) {
                    // Nothing identifies the destination, so this row can never be sent and is not
                    // displayed in any conversation either. Drop it rather than retry it on every
                    // reconnect for the life of the install.
                    Log.w(
                        "ChatRepositoryImpl",
                        "Discarding unsent message ${row.id}: no chatId or groupId"
                    )
                    messageDao.deleteMessageById(row.id)
                    continue
                }

                val alreadyStored = row.messageText
                    ?.takeIf { it.isNotBlank() }
                    ?.let { text ->
                        messageDao.findServerMessageMatching(
                            chatId = row.chatId,
                            groupId = row.groupId,
                            senderId = row.senderId,
                            text = text,
                            fromMillis = row.createdAt - duplicateWindowMillis,
                            toMillis = row.createdAt + duplicateWindowMillis
                        )
                    }

                if (alreadyStored != null) {
                    Log.i(
                        "ChatRepositoryImpl",
                        "Unsent message ${row.id} is already on the server as ${alreadyStored.id} - dropping the local copy"
                    )
                    messageDao.deleteMessageById(row.id)
                    refreshChatPreview(alreadyStored)
                    continue
                }

                resendUnsentMessage(row)
            }
        } catch (e: Exception) {
            Log.e("ChatRepositoryImpl", "flushUnsentMessages failed", e)
        } finally {
            isFlushingUnsent.set(false)
        }
    }

    /**
     * One retry attempt for a single unsent row. A row is retried at most once per reconnect and
     * left FAILED - visible as a failed send rather than an endless clock - if the retry also fails.
     */
    private suspend fun resendUnsentMessage(row: MessageEntity) {
        // Back to PENDING so the row shows as in-flight while the retry runs.
        messageDao.updateDeliveryStatus(row.id, "PENDING")

        val request = MessageRequest(
            chatId = row.chatId,
            groupId = row.groupId,
            // Deliberately null: the backend only falls back to recipientId when neither chatId nor
            // groupId is present, and the check above guarantees one of them is.
            recipientId = null,
            messageType = row.messageType,
            messageText = row.messageText,
            // Already a server-side path - uploadAttachment() runs before sendMessage(), so a row
            // that exists at all has had its file uploaded successfully.
            attachmentPath = row.attachmentPath,
            attachmentFileName = row.attachmentFileName,
            attachmentFileType = row.attachmentFileType,
            attachmentFileSize = row.attachmentFileSize
        )

        try {
            val response = chatApiService.sendHttpMessage(request)
            if (response.isSuccessful && response.body() != null) {
                val messageResp = response.body()!!
                val stored = keepBestStatus(messageResp.toEntity(messageResp.deliveryStatus ?: "SENT"))
                messageDao.deleteMessageById(row.id)
                messageDao.insertMessage(stored)
                refreshChatPreview(stored)
                Log.i("ChatRepositoryImpl", "Resent message ${row.id} as ${messageResp.id}")
            } else {
                Log.w(
                    "ChatRepositoryImpl",
                    "Resend of ${row.id} rejected: ${parseErrorMessage(response, "Failed to send message")}"
                )
                markSendFailed(row.id)
            }
        } catch (e: Exception) {
            Log.e("ChatRepositoryImpl", "Resend of ${row.id} failed", e)
            markSendFailed(row.id)
        }
    }

    /**
     * Points a chat's cached last-message preview at [message], but only when [message] really is
     * the newest one - resending an older queued message must not push a stale line to the top of
     * the chat list.
     */
    private fun refreshChatPreview(message: MessageEntity) {
        val resolvedChatId = message.groupId?.let { -it } ?: message.chatId ?: return
        val chat = chatDao.getChatById(resolvedChatId) ?: return
        if ((chat.lastMessageAt ?: 0L) > message.createdAt) return

        val previewText = if (message.messageText.isNullOrBlank() && message.attachmentPath != null) {
            when (message.messageType) {
                "IMAGE" -> "📷 Photo"
                "VIDEO" -> "🎥 Video"
                "VOICE" -> "🎤 Voice message"
                else -> "📎 Document"
            }
        } else {
            message.messageText
        }

        chatDao.insertChat(
            chat.copy(
                lastMessageText = previewText,
                lastMessageSenderId = message.senderId,
                lastMessageStatus = message.deliveryStatus,
                lastMessageAt = message.createdAt
            )
        )
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
                // The REST endpoint records the receipt and broadcasts it. Sending another
                // WebSocket receipt here created a duplicate race and inconsistent tick state.
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
                    // Incoming messages are marked READ because receiving one over an open socket
                    // means the app is in the foreground with the conversation available. For our
                    // own messages, trust what the server says rather than assuming "SENT" - by the
                    // time it broadcasts, it may already know the message was delivered.
                    val deliveryStatus = if (isOutgoing) (response.deliveryStatus ?: "SENT") else "READ"
                    
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
                    val existingRow = messageDao.getMessageById(response.id)
                    val isGenuinelyNewMessage = existingRow == null

                    // An edit has to come back to the reader's attention like a new message. Keyed
                    // on the text actually having changed rather than just on isEdited, so a
                    // repeated frame for an edit we have already applied - the socket frame and the
                    // FCM push both arrive when the app is in the foreground - cannot bump the badge
                    // twice for one edit.
                    //
                    // Deletes travel as MESSAGE_UPDATE too, hence the isDeleted guard: a message
                    // being withdrawn is not something to mark unread.
                    val isIncomingEdit = wsMessage.type == "MESSAGE_UPDATE" &&
                            !isOutgoing &&
                            response.isEdited &&
                            !response.isDeleted &&
                            existingRow != null &&
                            existingRow.messageText != response.messageText

                    val entity = keepBestStatus(response.toEntity(deliveryStatus))
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
                                        otherBio = null,
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
                        } else if (chat != null && isIncomingEdit) {
                            // The server has already dropped this reader's read receipt for the
                            // message (see MessageServiceImpl.editMessage), which is what makes it
                            // unread for real; a private chat's badge would therefore correct itself
                            // on the next syncChats(). Doing it locally as well shows the badge
                            // immediately, and is the *only* mechanism groups have - group unread is
                            // client-side only, with no server-side equivalent of
                            // countUnreadMessagesInChat. The local value is transient for private
                            // chats: syncChats() overwrites it with the server's authoritative count,
                            // so the two can never compound.
                            //
                            // The preview only moves if the edited message is the newest one.
                            // Editing something said hours ago must not rewrite the chat list to
                            // claim those are the conversation's latest words.
                            val latest = if (groupId != null) {
                                messageDao.getLatestMessageForGroup(groupId)
                            } else {
                                messageDao.getLatestMessageForChat(resolvedChatId)
                            }
                            if (latest?.id == response.id) {
                                chatDao.incrementUnreadAndUpdateLastMessage(
                                    chatId = resolvedChatId,
                                    text = response.messageText,
                                    senderId = response.senderId,
                                    at = response.createdAt.toEpochMillis()
                                )
                            } else {
                                chatDao.incrementUnreadCount(resolvedChatId)
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
                            //
                            // Only forwards, never back: a DELIVERED frame delayed behind the
                            // recipient's read receipt would otherwise pull a blue double tick back
                            // to grey.
                            if (statusRank(status) > statusRank(existingMessage.deliveryStatus)) {
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
                                val adopted = pending.copy(id = messageId, deliveryStatus = status)
                                messageDao.deleteMessageById(pending.id)
                                messageDao.insertMessage(adopted)
                                // The chat list keeps its own copy of the last message's status and
                                // this branch never refreshed it, so a chat whose newest message had
                                // just been delivered still showed the sending clock in the list.
                                val conversationId = adopted.groupId?.let { -it } ?: adopted.chatId
                                if (conversationId != null) {
                                    chatDao.updateLastMessageStatus(
                                        conversationId,
                                        adopted.senderId,
                                        adopted.messageText,
                                        status
                                    )
                                }
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
                val entities = messages.map { keepBestStatus(it.toEntity(it.deliveryStatus ?: "SENT")) }
                messageDao.insertMessages(entities)
                Result.success(Unit)
            } else {
                Result.failure(Exception(parseErrorMessage(response, "Failed to sync group messages")))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Applies an edit locally, then sends it and takes the server's word for the resulting tick.
     *
     * Two things here are deliberate.
     *
     * The server response is stored *without* [keepBestStatus]. An edit is the one case where
     * delivery genuinely moves backwards: editing a message the other person had already read means
     * they have now read something that is no longer there, so the server drops their read receipt
     * (see MessageServiceImpl.editMessage) and reports DELIVERED again - it is on their device, but
     * the new text has not been read. Passing that through keepBestStatus, whose entire job is to
     * refuse downgrades, clamped it straight back to READ and left the sender looking at a blue tick
     * for text nobody had seen. From here the tick behaves like a new message's: double grey until
     * they reopen the conversation, then blue again via the READ_RECEIPT frame.
     *
     * The chat-list preview only moves if the edited message is the newest one, and it is updated in
     * place rather than by rewriting the whole row - editing something said hours ago must not
     * rewrite the list to claim those are the conversation's latest words, nor reorder the list by
     * touching lastMessageAt.
     */
    override suspend fun editMessage(messageId: Long, newText: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            messageDao.updateMessageText(messageId, newText)
            val msgEntity = messageDao.getMessageById(messageId)

            val groupId = msgEntity?.groupId
            val chatId = msgEntity?.chatId
            val conversationId = when {
                groupId != null -> -groupId
                chatId != null -> chatId
                else -> null
            }
            val latest = when {
                groupId != null -> messageDao.getLatestMessageForGroup(groupId)
                chatId != null -> messageDao.getLatestMessageForChat(chatId)
                else -> null
            }
            val isNewestInConversation = latest?.id == messageId

            if (msgEntity != null && conversationId != null && isNewestInConversation) {
                chatDao.updateLastMessageOnly(
                    chatId = conversationId,
                    text = newText,
                    senderId = msgEntity.senderId,
                    at = msgEntity.createdAt
                )
            }

            if (messageId > 0) {
                val response = chatApiService.editMessage(messageId, newText)
                val body = response.body()
                if (response.isSuccessful && body != null) {
                    val serverStatus = body.deliveryStatus ?: "SENT"
                    messageDao.insertMessage(body.toEntity(serverStatus))
                    if (msgEntity != null && conversationId != null && isNewestInConversation) {
                        // Keyed on the new text, since updateLastMessageOnly above has already
                        // written it as the preview.
                        chatDao.updateLastMessageStatus(
                            chatId = conversationId,
                            senderId = msgEntity.senderId,
                            text = newText,
                            status = serverStatus
                        )
                    }
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
                    messageDao.insertMessage(keepBestStatus(body.toEntity(body.deliveryStatus ?: "SENT")))
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
                Result.success(body.map { it.toEntity(it.deliveryStatus ?: "SENT").toDomain() })
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
