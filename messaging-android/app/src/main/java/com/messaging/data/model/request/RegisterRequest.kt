package com.messaging.data.model.request

/**
 * RegisterRequest - DTO for registration request
 */
data class RegisterRequest(
    val username: String,
    val email: String,
    val password: String,
    val displayName: String,
    val phoneNumber: String? = null
)
