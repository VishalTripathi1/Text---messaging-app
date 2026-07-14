package com.messaging.data.model.response

/**
 * AuthResponse - DTO for authentication response
 */
data class AuthResponse(
    val userId: Long,
    val username: String,
    val email: String,
    val accessToken: String,
    val refreshToken: String,
    val expiresIn: Long,
    val tokenType: String
)
