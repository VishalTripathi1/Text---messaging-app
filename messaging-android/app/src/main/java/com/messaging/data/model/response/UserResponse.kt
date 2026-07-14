package com.messaging.data.model.response

/**
 * UserResponse - DTO for user information response
 */
data class UserResponse(
    val id: Long,
    val username: String,
    val email: String,
    val displayName: String,
    val bio: String? = null,
    val profilePictureUrl: String? = null,
    val status: String? = null,
    val lastSeen: String? = null,
    val isVerified: Boolean? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
)
