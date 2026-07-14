package com.messaging.presentation.screen.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.messaging.domain.entity.User
import com.messaging.domain.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * SearchViewModel - Coordinates search queries, contact lists, and chat creation hooks
 */
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val chatRepository: ChatRepository
) : ViewModel() {

    private val _contacts = MutableStateFlow<List<User>>(emptyList())
    val contacts: StateFlow<List<User>> = _contacts

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _chatCreatedEvent = MutableSharedFlow<Long>()
    val chatCreatedEvent: SharedFlow<Long> = _chatCreatedEvent

    init {
        loadContacts()
    }

    fun loadContacts() {
        viewModelScope.launch {
            _isLoading.value = true
            chatRepository.getContacts().onSuccess {
                _contacts.value = it
            }.onFailure {
                it.printStackTrace()
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
            chatRepository.searchContacts(query).onSuccess {
                _contacts.value = it
            }.onFailure {
                it.printStackTrace()
            }
            _isLoading.value = false
        }
    }

    fun startChat(recipientId: Long) {
        viewModelScope.launch {
            _isLoading.value = true
            chatRepository.getOrCreateChat(recipientId).onSuccess { chat ->
                _chatCreatedEvent.emit(chat.id)
            }.onFailure {
                it.printStackTrace()
            }
            _isLoading.value = false
        }
    }
}
