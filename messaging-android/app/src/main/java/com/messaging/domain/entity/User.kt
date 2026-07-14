package com.messaging.domain.entity

/**
 * User - Domain entity representing user profile details
 */
data class User(
    val id: Long,
    val username: String,
    val email: String,
    val displayName: String,
    val profilePictureUrl: String?,
    val bio: String?,
    val status: String,
    val lastSeen: String?
)
