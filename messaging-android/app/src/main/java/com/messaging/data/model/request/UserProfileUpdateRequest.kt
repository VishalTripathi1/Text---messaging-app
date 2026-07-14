package com.messaging.data.model.request

/**
 * UserProfileUpdateRequest - Request model to update user profile
 */
data class UserProfileUpdateRequest(
    val displayName: String?,
    val bio: String?,
    val profilePictureUrl: String?
)
