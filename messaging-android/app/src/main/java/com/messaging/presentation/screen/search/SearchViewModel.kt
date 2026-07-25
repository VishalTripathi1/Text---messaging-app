package com.messaging.presentation.screen.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.messaging.data.mapper.toDomain
import com.messaging.domain.entity.User
import com.messaging.domain.repository.ChatRepository
import com.messaging.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * SearchViewModel - Coordinates search queries, contact lists, and contact syncing
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val userRepository: UserRepository
) : ViewModel() {

    private val _contacts = MutableStateFlow<List<User>>(emptyList())
    val contacts: StateFlow<List<User>> = _contacts

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _chatCreatedChannel = Channel<Long>(Channel.BUFFERED)
    val chatCreatedEvent: Flow<Long> = _chatCreatedChannel.receiveAsFlow()

    init {
        loadContacts()
    }

    fun loadContacts() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            chatRepository.getContacts().onSuccess {
                _contacts.value = it
            }.onFailure {
                _errorMessage.value = it.localizedMessage ?: "Failed to load contacts"
            }
            _isLoading.value = false
        }
    }

    fun searchContacts(query: String) {
        if (query.isBlank()) {
            loadContacts()
            return
        }
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            chatRepository.searchContacts(query).onSuccess {
                _contacts.value = it
            }.onFailure {
                _errorMessage.value = it.localizedMessage ?: "Search failed"
            }
            _isLoading.value = false
        }
    }

    fun syncPhoneContacts(phoneNumbers: List<String>) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            userRepository.syncContacts(phoneNumbers).onSuccess { userResponses ->
                _contacts.value = userResponses.map { it.toDomain() }
            }.onFailure {
                _errorMessage.value = it.localizedMessage ?: "Contact sync failed"
            }
            _isLoading.value = false
        }
    }

    fun startChat(recipientId: Long) {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            chatRepository.getOrCreateChat(recipientId).onSuccess { chat ->
                _chatCreatedChannel.send(chat.id)
            }.onFailure {
                _errorMessage.value = it.localizedMessage ?: "Failed to start chat"
            }
            _isLoading.value = false
        }
    }

    fun clearError() {
        _errorMessage.value = null
    }
}
