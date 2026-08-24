package com.messaging.data.repository

import android.util.Log
import com.messaging.data.datasource.remote.ChatApiService
import com.messaging.data.model.request.ContactSyncRequest
import com.messaging.data.model.request.UserProfileUpdateRequest
import com.messaging.data.model.request.UserSettingsUpdateRequest
import com.messaging.data.model.response.UserResponse
import com.messaging.data.model.response.UserSettingsResponse
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.RequestBody.Companion.toRequestBody
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
                throw Exception(parseErrorMessage(response, "Failed to fetch user"))
            }
        }.onFailure {
            Log.e("UserRepositoryImpl", "Error fetching current user", it)
        }
    }

    override suspend fun syncContacts(phoneNumbers: List<String>): Result<List<UserResponse>> = withContext(Dispatchers.IO) {
        runCatching {
            val response = chatApiService.syncContacts(ContactSyncRequest(phoneNumbers))
            if (response.isSuccessful && response.body() != null) {
                response.body()!!
            } else {
                throw Exception(parseErrorMessage(response, "Failed to sync contacts"))
            }
        }.onFailure {
            Log.e("UserRepositoryImpl", "Error syncing contacts", it)
        }
    }

    override suspend fun uploadProfilePicture(
        fileBytes: ByteArray,
        fileName: String,
        mimeType: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val body = fileBytes.toRequestBody(mimeType.toMediaTypeOrNull())
            val part = MultipartBody.Part.createFormData("file", fileName, body)
            val response = chatApiService.uploadAttachment(part)
            if (response.isSuccessful) {
                response.body()?.get("filePath") as? String
                    ?: throw Exception("Upload response did not include a file path")
            } else {
                throw Exception(parseErrorMessage(response, "Failed to upload profile picture"))
            }
        }.onFailure {
            Log.e("UserRepositoryImpl", "Error uploading profile picture", it)
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
                throw Exception(parseErrorMessage(response, "Failed to update profile"))
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
                throw Exception(parseErrorMessage(response, "Failed to fetch settings"))
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
                throw Exception(parseErrorMessage(response, "Failed to update settings"))
            }
        }.onFailure {
            Log.e("UserRepositoryImpl", "Error updating settings", it)
        }
    }

    /** See the identical helper in ChatRepositoryImpl for why this replaced response.message(). */
    private fun parseErrorMessage(response: retrofit2.Response<*>, fallback: String): String {
        val bodyText = try {
            response.errorBody()?.string()
        } catch (e: Exception) {
            null
        }
        if (!bodyText.isNullOrBlank()) {
            try {
                val json = org.json.JSONObject(bodyText)
                if (json.has("message")) {
                    return json.getString("message")
                }
            } catch (e: Exception) {
                // Not JSON - fall through to the generic fallback below.
            }
        }
        return "$fallback (HTTP ${response.code()})"
    }
}
