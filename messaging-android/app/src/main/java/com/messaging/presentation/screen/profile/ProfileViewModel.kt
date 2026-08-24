package com.messaging.presentation.screen.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.ByteArrayOutputStream
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

    fun uploadProfilePicture(context: Context, uri: Uri, onUploaded: (String) -> Unit) {
        viewModelScope.launch {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    ?: throw IllegalStateException("Unable to read selected image")
            }.mapCatching { source ->
                val scale = minOf(1f, 512f / maxOf(source.width, source.height).toFloat())
                val resized = if (scale < 1f) Bitmap.createScaledBitmap(
                    source, (source.width * scale).toInt(), (source.height * scale).toInt(), true
                ) else source
                val compressed = ByteArrayOutputStream().also { output ->
                    resized.compress(Bitmap.CompressFormat.JPEG, 80, output)
                }.toByteArray()
                if (resized !== source) resized.recycle()
                if (source.isRecycled.not()) source.recycle()
                userRepository.uploadProfilePicture(compressed, "profile.jpg", "image/jpeg").getOrThrow()
            }.onSuccess(onUploaded)
                .onFailure { _errorMessage.value = it.message ?: "Failed to upload profile picture" }
        }
    }

    fun clearMessages() {
        _errorMessage.value = null
        _successMessage.value = null
    }
}
