package com.messaging.domain.repository

import com.messaging.data.model.request.UserProfileUpdateRequest
import com.messaging.data.model.request.UserSettingsUpdateRequest
import com.messaging.data.model.response.UserResponse
import com.messaging.data.model.response.UserSettingsResponse

/**
 * UserRepository - Domain interface for user profile and settings operations
 */
interface UserRepository {
    suspend fun getCurrentUser(): Result<UserResponse>
    suspend fun updateProfile(displayName: String?, bio: String?, profilePictureUrl: String?): Result<UserResponse>
    suspend fun getSettings(): Result<UserSettingsResponse>
    suspend fun updateSettings(
        theme: String?,
        notificationsEnabled: Boolean?,
        soundEnabled: Boolean?,
        vibrationEnabled: Boolean?,
        showReadReceipts: Boolean?,
        showTypingIndicator: Boolean?
    ): Result<UserSettingsResponse>
}
