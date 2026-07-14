package com.messaging.domain.repository

import com.messaging.data.model.request.LoginRequest
import com.messaging.data.model.request.RegisterRequest
import com.messaging.data.model.response.AuthResponse
import kotlinx.coroutines.flow.Flow

/**
 * AuthRepository - Repository interface for authentication logic
 */
interface AuthRepository {
    suspend fun register(request: RegisterRequest): Result<AuthResponse>
    suspend fun login(request: LoginRequest): Result<AuthResponse>
    suspend fun refreshToken(refreshToken: String): Result<AuthResponse>
    suspend fun validateToken(token: String): Result<Boolean>
    suspend fun logout(): Result<Unit>
    
    val accessToken: Flow<String?>
    val refreshToken: Flow<String?>
    val userId: Flow<Long?>
    val username: Flow<String?>
    suspend fun clearSession()
}
