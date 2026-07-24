package com.messaging.presentation.screen.chat

import android.util.Log
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
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
                                    _isPeerTyping.value = payloadMap["isTyping"] as? Boolean ?: false
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                    }
                }
            }
        }
        syncMessages()
        markAsRead()
    }

    fun syncMessages() {
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
            val recipientId = otherUserId
            chatRepository.sendMessage(
                recipientId = recipientId,
                chatId = chatId,
                text = text,
                type = type
            )
        }
    }

    fun sendMediaMessage(fileBytes: ByteArray, fileName: String, mimeType: String, type: String) {
        viewModelScope.launch {
            chatRepository.uploadAttachment(fileBytes, fileName, mimeType)
                .onSuccess { responseMap ->
                    val filePath = responseMap["filePath"] as? String ?: ""
                    val size = (responseMap["fileSize"] as? Number)?.toLong() ?: fileBytes.size.toLong()
                    
                    chatRepository.sendMessage(
                        recipientId = otherUserId,
                        chatId = chatId,
                        text = "[Attachment: $fileName]",
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
