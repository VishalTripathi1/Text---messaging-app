package com.messaging.presentation.screen.group

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.messaging.data.model.response.GroupResponse
import com.messaging.domain.entity.User
import com.messaging.domain.repository.ChatRepository
import com.messaging.util.TokenManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * GroupInfoViewModel - Backs the Group Info screen: viewing members and their roles, renaming
 * the group (admins only), adding/removing members, and leaving the group.
 *
 * The backend already enforced admin-only permission on removing other members and already
 * tracked an ADMIN/MEMBER role per membership - none of it was ever exposed through the API or
 * a screen, which is why the app previously had no member list, no rename control, and no way to
 * add or remove anyone after group creation.
 */
@HiltViewModel
class GroupInfoViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val chatRepository: ChatRepository,
    private val tokenManager: TokenManager
) : ViewModel() {

    val groupId: Long = checkNotNull(savedStateHandle.get<Long>("groupId"))

    var currentUserId: Long = 0
        private set

    private val _group = MutableStateFlow<GroupResponse?>(null)
    val group: StateFlow<GroupResponse?> = _group.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _leftGroup = MutableStateFlow(false)
    val leftGroup: StateFlow<Boolean> = _leftGroup.asStateFlow()

    // Add-members sheet state
    private val _addableContacts = MutableStateFlow<List<User>>(emptyList())
    val addableContacts: StateFlow<List<User>> = _addableContacts.asStateFlow()

    private val _selectedNewMemberIds = MutableStateFlow<Set<Long>>(emptySet())
    val selectedNewMemberIds: StateFlow<Set<Long>> = _selectedNewMemberIds.asStateFlow()

    init {
        viewModelScope.launch {
            currentUserId = tokenManager.userId.firstOrNull() ?: 0
        }
        loadGroup()
    }

    fun loadGroup() {
        viewModelScope.launch {
            _isLoading.value = true
            chatRepository.getGroupDetails(groupId)
                .onSuccess { _group.value = it }
                .onFailure { _error.value = it.localizedMessage ?: "Failed to load group" }
            _isLoading.value = false
        }
    }

    fun renameGroup(newName: String) {
        val trimmed = newName.trim()
        if (trimmed.isEmpty() || trimmed == _group.value?.groupName) return
        viewModelScope.launch {
            chatRepository.updateGroup(groupId, groupName = trimmed, description = null)
                .onSuccess { _group.value = it }
                .onFailure { _error.value = it.localizedMessage ?: "Failed to rename group" }
        }
    }

    fun removeMember(userId: Long) {
        viewModelScope.launch {
            chatRepository.removeGroupMember(groupId, userId)
                .onSuccess { loadGroup() }
                .onFailure { _error.value = it.localizedMessage ?: "Failed to remove member" }
        }
    }

    fun leaveGroup() {
        viewModelScope.launch {
            chatRepository.removeGroupMember(groupId, currentUserId)
                .onSuccess { _leftGroup.value = true }
                .onFailure { _error.value = it.localizedMessage ?: "Failed to leave group" }
        }
    }

    fun openAddMembers() {
        viewModelScope.launch {
            chatRepository.getContacts()
                .onSuccess { all ->
                    val existingIds = _group.value?.members?.map { it.user.id }?.toSet() ?: emptySet()
                    _addableContacts.value = all.filterNot { existingIds.contains(it.id) }
                }
                .onFailure { _error.value = it.localizedMessage ?: "Failed to load contacts" }
        }
    }

    fun toggleNewMemberSelection(userId: Long) {
        val current = _selectedNewMemberIds.value.toMutableSet()
        if (!current.add(userId)) current.remove(userId)
        _selectedNewMemberIds.value = current
    }

    fun confirmAddMembers() {
        val ids = _selectedNewMemberIds.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            chatRepository.addGroupMembers(groupId, ids)
                .onSuccess {
                    _selectedNewMemberIds.value = emptySet()
                    _addableContacts.value = emptyList()
                    loadGroup()
                }
                .onFailure { _error.value = it.localizedMessage ?: "Failed to add members" }
        }
    }

    fun cancelAddMembers() {
        _addableContacts.value = emptyList()
        _selectedNewMemberIds.value = emptySet()
    }

    fun clearError() {
        _error.value = null
    }
}
