package com.messaging.presentation.screen.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.messaging.data.model.response.UserResponse
import com.messaging.domain.repository.UserRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ProfileViewModel - Manages user profile retrieval and edits
 */
@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val userRepository: UserRepository
) : ViewModel() {

    private val _profile = MutableStateFlow<UserResponse?>(null)
    val profile: StateFlow<UserResponse?> = _profile

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage

    private val _successMessage = MutableStateFlow<String?>(null)
    val successMessage: StateFlow<String?> = _successMessage

    init {
        loadProfile()
    }

    fun loadProfile() {
        viewModelScope.launch {
            _isLoading.value = true
            _errorMessage.value = null
            userRepository.getCurrentUser()
                .onSuccess { _profile.value = it }
                .onFailure { _errorMessage.value = it.message ?: "Failed to load profile" }
            _isLoading.value = false
        }
    }

    fun saveProfile(displayName: String, bio: String, profilePictureUrl: String) {
        viewModelScope.launch {
            _isSaving.value = true
            _errorMessage.value = null
            _successMessage.value = null
            userRepository.updateProfile(
                displayName = displayName.ifBlank { null },
                bio = bio.ifBlank { null },
                profilePictureUrl = profilePictureUrl.ifBlank { null }
            )
                .onSuccess {
                    _profile.value = it
                    _successMessage.value = "Profile updated successfully!"
                }
                .onFailure { _errorMessage.value = it.message ?: "Failed to update profile" }
            _isSaving.value = false
        }
    }

    fun clearMessages() {
        _errorMessage.value = null
        _successMessage.value = null
    }
}
