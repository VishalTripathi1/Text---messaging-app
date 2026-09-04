package com.messaging.presentation.screen.contact

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.messaging.domain.entity.Chat
import com.messaging.domain.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ContactInfoViewModel - Backs the private-chat "Contact Info" page: the other person's avatar,
 * name, username, bio, status, and block/unblock - the same full-page pattern WhatsApp/Telegram
 * use, tapping the chat header used to only ever open a small popup dialog with a subset of this.
 */
@HiltViewModel
class ContactInfoViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val chatRepository: ChatRepository
) : ViewModel() {

    val chatId: Long = checkNotNull(savedStateHandle.get<Long>("chatId"))

    val chat: StateFlow<Chat?> = chatRepository.getChatsFlow()
        .map { chats -> chats.find { it.id == chatId } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _isBlocked = MutableStateFlow(false)
    val isBlocked: StateFlow<Boolean> = _isBlocked.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private var hasLoadedBlockStatus = false

    init {
        // chat is a stateIn'd Flow with WhileSubscribed(5000) - it has no value yet at
        // construction time, only once the screen actually starts collecting it. Reacting to
        // each emission (rather than checking chat.value once here) means the block-status
        // lookup fires as soon as the other user's id is actually known, instead of silently
        // no-oping against a still-null chat. The flag just avoids re-fetching on every
        // unrelated update to this chat's row (e.g. a new last message).
        viewModelScope.launch {
            chat.collect { current ->
                if (!hasLoadedBlockStatus && current?.otherUser?.id != null) {
                    hasLoadedBlockStatus = true
                    refreshBlockStatus()
                }
            }
        }
    }

    private fun refreshBlockStatus() {
        viewModelScope.launch {
            val otherUserId = chat.value?.otherUser?.id ?: return@launch
            chatRepository.isUserBlocked(otherUserId).onSuccess { _isBlocked.value = it }
        }
    }

    fun toggleBlock() {
        val otherUserId = chat.value?.otherUser?.id ?: return
        viewModelScope.launch {
            val result = if (_isBlocked.value) {
                chatRepository.unblockUser(otherUserId)
            } else {
                chatRepository.blockUser(otherUserId)
            }
            result.onSuccess {
                _isBlocked.value = !_isBlocked.value
            }.onFailure {
                _error.value = it.localizedMessage ?: "Failed to update block status"
            }
        }
    }

    fun clearError() {
        _error.value = null
    }
}
