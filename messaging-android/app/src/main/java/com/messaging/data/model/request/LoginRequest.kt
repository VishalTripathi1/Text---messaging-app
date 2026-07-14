package com.messaging.data.model.request

/**
 * LoginRequest - DTO for login request
 */
data class LoginRequest(
    val username: String,
    val password: String,
    val deviceId: String,
    val deviceName: String? = null
)
