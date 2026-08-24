package com.messaging.presentation.screen.profile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
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
                val orientation = context.contentResolver.openFileDescriptor(uri, "r")?.use {
                    ExifInterface(it.fileDescriptor).getAttributeInt(
                        ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                    )
                } ?: ExifInterface.ORIENTATION_NORMAL
                val source = context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                    ?: throw IllegalStateException("Unable to read selected image")
                source to orientation
            }.mapCatching { (source, orientation) ->
                val matrix = Matrix()
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
                    ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
                }
                val oriented = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
                val scale = minOf(1f, 512f / maxOf(oriented.width, oriented.height).toFloat())
                val resized = if (scale < 1f) Bitmap.createScaledBitmap(
                    oriented, (oriented.width * scale).toInt(), (oriented.height * scale).toInt(), true
                ) else oriented
                val compressed = ByteArrayOutputStream().also { output ->
                    resized.compress(Bitmap.CompressFormat.JPEG, 80, output)
                }.toByteArray()
                if (resized !== oriented) resized.recycle()
                if (oriented !== source) oriented.recycle()
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
