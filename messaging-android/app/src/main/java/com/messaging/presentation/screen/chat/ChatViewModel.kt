package com.messaging.presentation.screen.chat

import android.util.Log
import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.messaging.data.datasource.local.ChatDao
import com.messaging.data.datasource.remote.WebSocketClient
import com.messaging.data.model.WebSocketMessage
import com.messaging.data.model.request.MessageRequest
import com.messaging.domain.entity.Message
import com.messaging.domain.repository.ChatRepository
import com.messaging.util.TokenManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job

/**
 * ChatViewModel - Controls conversation events, message logs, and typing notifications
 */
@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val chatDao: ChatDao,
    private val webSocketClient: WebSocketClient,
    private val tokenManager: TokenManager,
    private val gson: Gson,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val chatId: Long = checkNotNull(savedStateHandle["chatId"])

    val messages: StateFlow<List<Message>> = if (chatId < 0) {
        chatRepository.getGroupMessagesFlow(-chatId)
    } else {
        chatRepository.getMessagesFlow(chatId)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _otherUserDisplayName = MutableStateFlow("")
    val otherUserDisplayName: StateFlow<String> = _otherUserDisplayName

    private val _otherUserStatus = MutableStateFlow("OFFLINE")
    val otherUserStatus: StateFlow<String> = _otherUserStatus

    private val _isPeerTyping = MutableStateFlow(false)
    val isPeerTyping: StateFlow<Boolean> = _isPeerTyping

    private var otherUserId: Long? = null
    var currentUserId: Long = 0
    private var peerTypingTimeout: Job? = null

    // Becomes true once chat metadata (in particular otherUserId, needed to route messages to
    // the recipient in real time over WebSocket) has finished loading. sendMessage() suspends
    // on this so that messages typed immediately after opening a chat aren't sent with a null
    // recipientId - previously that race meant the message would save, but never reach the
    // other person live, making it look like "chat isn't working between two people".
    private val chatReady = CompletableDeferred<Unit>()

    init {
        chatRepository.connectWebSocket()
        viewModelScope.launch {
            currentUserId = tokenManager.userId.firstOrNull() ?: 0
            var chat = withContext(Dispatchers.IO) {
                chatDao.getChatById(chatId)
            }
            if (chat == null && chatId > 0) {
                chatRepository.syncChats()
                chat = withContext(Dispatchers.IO) {
                    chatDao.getChatById(chatId)
                }
            } else if (chatId > 0) {
                // Always refresh from the server too so the header (name/status) reflects the
                // latest profile info, not a possibly stale local cache.
                chatRepository.syncChats()
                chat = withContext(Dispatchers.IO) {
                    chatDao.getChatById(chatId)
                } ?: chat
            }
            if (chat != null) {
                if (chat.isGroup) {
                    otherUserId = null
                    _otherUserDisplayName.value = chat.groupName ?: "Group Chat"
                    _otherUserStatus.value = chat.groupDescription ?: "Group conversation"
                } else {
                    otherUserId = chat.otherUserId
                    _otherUserDisplayName.value = chat.otherDisplayName ?: ""
                    _otherUserStatus.value = chat.otherStatus ?: "OFFLINE"
                    
                    launch {
                        webSocketClient.incomingMessages
                            .filter { it.type == "TYPING" && it.chatId == chatId && it.senderId == chat?.otherUserId }
                            .collect { wsMessage ->
                                try {
                                    val jsonStr = gson.toJson(wsMessage.payload)
                                    val payloadMap = gson.fromJson(jsonStr, Map::class.java)
                                    val isTyping = payloadMap["isTyping"] as? Boolean ?: false
                                    _isPeerTyping.value = isTyping
                                    peerTypingTimeout?.cancel()
                                    if (isTyping) {
                                        peerTypingTimeout = launch {
                                            kotlinx.coroutines.delay(5_000)
                                            _isPeerTyping.value = false
                                        }
                                    }
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                    }
                }
            }
            chatReady.complete(Unit)
        }
        syncMessages()
        markAsRead()

        // Keep the header's online/offline status live. Previously _otherUserStatus was only
        // ever set once (the snapshot read above), so a USER_STATUS update arriving over the
        // WebSocket while this chat screen was already open wouldn't be reflected until the
        // user backed out and reopened the chat.
        if (chatId > 0) {
            viewModelScope.launch {
                chatDao.getChatByIdFlow(chatId)
                    .flowOn(Dispatchers.IO)
                    .collect { chat ->
                        if (chat != null && !chat.isGroup) {
                            _otherUserStatus.value = chat.otherStatus ?: "OFFLINE"
                        }
                    }
            }
        }
    }

    fun syncMessages() {
        // Also re-verifies the WebSocket connection - the OS can silently drop it while the app
        // is backgrounded/the screen is locked, and reconnecting is idempotent (see
        // WebSocketClient's isConnecting/connectionStatus guard) so it's safe to call on every
        // resume regardless of current state.
        chatRepository.connectWebSocket()
        viewModelScope.launch {
            chatRepository.syncMessages(chatId)
        }
    }

    fun markAsRead() {
        viewModelScope.launch {
            chatRepository.markChatAsRead(chatId)
        }
    }

    fun sendMessage(text: String, type: String = "TEXT") {
        if (text.isBlank()) return
        viewModelScope.launch {
            // Wait (briefly) for chat metadata to finish loading so recipientId is populated -
            // otherwise the message is saved but never delivered live to the other person.
            withTimeoutOrNull(3000) { chatReady.await() }
            val recipientId = otherUserId
            chatRepository.sendMessage(
                recipientId = recipientId,
                chatId = chatId,
                text = text,
                type = type
            )
        }
    }

    fun sendMediaMessage(
        context: Context,
        uri: Uri,
        fileName: String,
        mimeType: String,
        type: String,
        caption: String
    ) {
        viewModelScope.launch {
            withTimeoutOrNull(3000) { chatReady.await() }
            // Bytes only exist during upload; the UI keeps a URI while the attachment is staged.
            val fileBytes = try {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: throw IllegalStateException("Unable to read selected attachment")
            } catch (t: Throwable) {
                Log.e("ChatViewModel", "Failed to read attachment", t)
                return@launch
            }
            chatRepository.uploadAttachment(fileBytes, fileName, mimeType)
                .onSuccess { responseMap ->
                    val filePath = responseMap["filePath"] as? String ?: ""
                    val size = (responseMap["fileSize"] as? Number)?.toLong() ?: fileBytes.size.toLong()
                    
                    chatRepository.sendMessage(
                        recipientId = otherUserId,
                        chatId = chatId,
                        text = caption.trim(),
                        type = type,
                        attachmentPath = filePath,
                        attachmentFileName = fileName,
                        attachmentFileType = mimeType,
                        attachmentFileSize = size
                    )
                }
                .onFailure { t ->
                    Log.e("ChatViewModel", "Failed to upload attachment", t)
                }
        }
    }

    fun sendTypingState(isTyping: Boolean) {
        val recipientId = otherUserId ?: return
        viewModelScope.launch {
            val wsMessage = WebSocketMessage(
                type = "TYPING",
                recipientId = recipientId,
                chatId = chatId,
                payload = mapOf("isTyping" to isTyping)
            )
            webSocketClient.sendMessage(wsMessage)
        }
    }

    fun editMessage(messageId: Long, newText: String) {
        viewModelScope.launch {
            chatRepository.editMessage(messageId, newText)
        }
    }

    fun deleteMessage(messageId: Long) {
        viewModelScope.launch {
            chatRepository.deleteMessage(messageId)
        }
    }

    fun addReaction(messageId: Long, reaction: String) {
        viewModelScope.launch {
            chatRepository.addReaction(messageId, reaction)
        }
    }

    fun removeReaction(messageId: Long) {
        viewModelScope.launch {
            chatRepository.removeReaction(messageId)
        }
    }

    fun toggleReaction(messageId: Long, reaction: String) {
        viewModelScope.launch {
            chatRepository.toggleReaction(messageId, reaction)
        }
    }

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery

    private val _searchResults = MutableStateFlow<List<Message>>(emptyList())
    val searchResults: StateFlow<List<Message>> = _searchResults

    fun searchMessages(query: String) {
        _searchQuery.value = query
        if (query.isBlank()) {
            _searchResults.value = emptyList()
            return
        }
        viewModelScope.launch {
            chatRepository.searchMessages(query)
                .onSuccess {
                    _searchResults.value = it
                }
        }
    }
}
