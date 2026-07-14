package com.messaging.data.repository

import android.util.Log
import com.messaging.data.datasource.remote.ChatApiService
import com.messaging.data.model.request.UserProfileUpdateRequest
import com.messaging.data.model.request.UserSettingsUpdateRequest
import com.messaging.data.model.response.UserResponse
import com.messaging.data.model.response.UserSettingsResponse
import com.messaging.domain.repository.UserRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UserRepositoryImpl - Coordinates profile and settings API calls
 */
@Singleton
class UserRepositoryImpl @Inject constructor(
    private val chatApiService: ChatApiService
) : UserRepository {

    override suspend fun getCurrentUser(): Result<UserResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val response = chatApiService.getCurrentUser()
            if (response.isSuccessful && response.body() != null) {
                response.body()!!
            } else {
                throw Exception("Failed to fetch user: ${response.message()}")
            }
        }.onFailure {
            Log.e("UserRepositoryImpl", "Error fetching current user", it)
        }
    }

    override suspend fun updateProfile(
        displayName: String?,
        bio: String?,
        profilePictureUrl: String?
    ): Result<UserResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val request = UserProfileUpdateRequest(
                displayName = displayName,
                bio = bio,
                profilePictureUrl = profilePictureUrl
            )
            val response = chatApiService.updateProfile(request)
            if (response.isSuccessful && response.body() != null) {
                response.body()!!
            } else {
                throw Exception("Failed to update profile: ${response.message()}")
            }
        }.onFailure {
            Log.e("UserRepositoryImpl", "Error updating profile", it)
        }
    }

    override suspend fun getSettings(): Result<UserSettingsResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val response = chatApiService.getSettings()
            if (response.isSuccessful && response.body() != null) {
                response.body()!!
            } else {
                throw Exception("Failed to fetch settings: ${response.message()}")
            }
        }.onFailure {
            Log.e("UserRepositoryImpl", "Error fetching settings", it)
        }
    }

    override suspend fun updateSettings(
        theme: String?,
        notificationsEnabled: Boolean?,
        soundEnabled: Boolean?,
        vibrationEnabled: Boolean?,
        showReadReceipts: Boolean?,
        showTypingIndicator: Boolean?
    ): Result<UserSettingsResponse> = withContext(Dispatchers.IO) {
        runCatching {
            val request = UserSettingsUpdateRequest(
                theme = theme,
                notificationsEnabled = notificationsEnabled,
                soundEnabled = soundEnabled,
                vibrationEnabled = vibrationEnabled,
                showReadReceipts = showReadReceipts,
                showTypingIndicator = showTypingIndicator
            )
            val response = chatApiService.updateSettings(request)
            if (response.isSuccessful && response.body() != null) {
                response.body()!!
            } else {
                throw Exception("Failed to update settings: ${response.message()}")
            }
        }.onFailure {
            Log.e("UserRepositoryImpl", "Error updating settings", it)
        }
    }
}
