package com.messaging.presentation.screen.group

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.messaging.domain.entity.User
import com.messaging.domain.repository.ChatRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CreateGroupViewModel @Inject constructor(
    private val chatRepository: ChatRepository
) : ViewModel() {

    private val _groupName = MutableStateFlow("")
    val groupName: StateFlow<String> = _groupName.asStateFlow()

    private val _description = MutableStateFlow("")
    val description: StateFlow<String> = _description.asStateFlow()

    private val _contacts = MutableStateFlow<List<User>>(emptyList())
    val contacts: StateFlow<List<User>> = _contacts.asStateFlow()

    private val _selectedContactIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedContactIds: StateFlow<Set<Long>> = _selectedContactIds.asStateFlow()

    private val _isCreating = MutableStateFlow(false)
    val isCreating: StateFlow<Boolean> = _isCreating.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _creationSuccess = MutableStateFlow(false)
    val creationSuccess: StateFlow<Boolean> = _creationSuccess.asStateFlow()

    init {
        loadContacts()
    }

    fun onGroupNameChanged(name: String) {
        _groupName.value = name
    }

    fun onDescriptionChanged(desc: String) {
        _description.value = desc
    }

    fun toggleContactSelection(userId: Long) {
        val current = _selectedContactIds.value.toMutableSet()
        if (current.contains(userId)) {
            current.remove(userId)
        } else {
            current.add(userId)
        }
        _selectedContactIds.value = current
    }

    private fun loadContacts() {
        viewModelScope.launch {
            chatRepository.getContacts()
                .onSuccess { list ->
                    _contacts.value = list
                }
                .onFailure { t ->
                    _error.value = "Failed to load contacts: ${t.localizedMessage}"
                }
        }
    }

    fun createGroup() {
        val name = _groupName.value.trim()
        val desc = _description.value.trim()
        val members = _selectedContactIds.value.toList()

        if (name.isEmpty()) {
            _error.value = "Group name cannot be empty"
            return
        }
        if (members.size < 2) {
            // Mirrors GroupServiceImpl.createGroup()'s server-side check - catching it here
            // means an immediate, clear message instead of a round-trip just to find out.
            _error.value = "Select at least 2 other members to create a group"
            return
        }

        viewModelScope.launch {
            _isCreating.value = true
            _error.value = null
            chatRepository.createGroup(name, desc, members)
                .onSuccess {
                    _creationSuccess.value = true
                }
                .onFailure { t ->
                    _error.value = "Failed to create group: ${t.localizedMessage}"
                }
            _isCreating.value = false
        }
    }
}