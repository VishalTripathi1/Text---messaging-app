package com.messaging.presentation.screen.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.messaging.domain.entity.Chat
import com.messaging.domain.repository.ChatRepository
import com.messaging.util.TokenManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ChatListViewModel - Manages the Chat Dashboard and connects WebSocket
 */
@HiltViewModel
class ChatListViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val tokenManager: TokenManager
) : ViewModel() {

    val chats: StateFlow<List<Chat>> = chatRepository.getChatsFlow()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isConnected: StateFlow<Boolean> = chatRepository.observeWebSocketConnection()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val currentUserId: StateFlow<Long> = tokenManager.userId
        .map { it ?: 0L }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing

    init {
        connectWebSocket()
        syncChats()
    }

    fun syncChats() {
        viewModelScope.launch {
            _isSyncing.value = true
            chatRepository.syncChats()
            _isSyncing.value = false
        }
    }

    fun connectWebSocket() {
        chatRepository.connectWebSocket()
    }

    fun pinChat(chatId: Long, pinned: Boolean) {
        viewModelScope.launch {
            chatRepository.togglePinChat(chatId, pinned)
            syncChats()
        }
    }

    fun archiveChat(chatId: Long, archived: Boolean) {
        viewModelScope.launch {
            chatRepository.toggleArchiveChat(chatId, archived)
            syncChats()
        }
    }

    fun deleteChat(chatId: Long) {
        viewModelScope.launch {
            chatRepository.deleteChat(chatId)
        }
    }
}
