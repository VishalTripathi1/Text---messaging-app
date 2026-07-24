package com.messaging.data.repository

import com.messaging.data.datasource.remote.AuthApiService
import com.messaging.data.model.request.LoginRequest
import com.messaging.data.model.request.RegisterRequest
import com.messaging.data.model.response.AuthResponse
import com.messaging.domain.repository.AuthRepository
import com.messaging.util.TokenManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.io.IOException
import javax.inject.Inject

/**
 * AuthRepositoryImpl - Repository implementation mapping remote endpoints to local persistence
 */
class AuthRepositoryImpl @Inject constructor(
    private val authApiService: AuthApiService,
    private val tokenManager: TokenManager
) : AuthRepository {

    override suspend fun register(request: RegisterRequest): Result<AuthResponse> {
        return try {
            val response = authApiService.register(request)
            if (response.isSuccessful && response.body() != null) {
                val authResponse = response.body()!!
                tokenManager.saveTokens(
                    authResponse.accessToken,
                    authResponse.refreshToken,
                    authResponse.userId,
                    authResponse.username
                )
                Result.success(authResponse)
            } else {
                Result.failure(Exception(parseErrorMessage(response.errorBody()?.string()) ?: "Registration failed"))
            }
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun login(request: LoginRequest): Result<AuthResponse> {
        return try {
            val response = authApiService.login(request)
            if (response.isSuccessful && response.body() != null) {
                val authResponse = response.body()!!
                tokenManager.saveTokens(
                    authResponse.accessToken,
                    authResponse.refreshToken,
                    authResponse.userId,
                    authResponse.username
                )
                Result.success(authResponse)
            } else {
                Result.failure(Exception(parseErrorMessage(response.errorBody()?.string()) ?: "Login failed"))
            }
        } catch (e: IOException) {
            Result.failure(e)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun parseErrorMessage(errorBody: String?): String? {
        if (errorBody.isNullOrBlank()) return null
        return try {
            val json = org.json.JSONObject(errorBody)
            if (json.has("message")) json.getString("message") else errorBody
        } catch (e: Exception) {
            errorBody
        }
    }

    override suspend fun refreshToken(refreshToken: String): Result<AuthResponse> {
        return try {
            val response = authApiService.refreshToken(refreshToken)
            if (response.isSuccessful && response.body() != null) {
                val authResponse = response.body()!!
                tokenManager.saveTokens(
                    authResponse.accessToken,
                    authResponse.refreshToken,
                    authResponse.userId,
                    authResponse.username
                )
                Result.success(authResponse)
            } else {
                Result.failure(Exception("Token refresh failed"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun validateToken(token: String): Result<Boolean> {
        return try {
            val response = authApiService.validateToken(token)
            if (response.isSuccessful) {
                Result.success(response.body() ?: false)
            } else {
                Result.success(false)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun logout(): Result<Unit> {
        return try {
            val token = tokenManager.accessToken.first()
            if (token != null) {
                authApiService.logout("Bearer $token")
            }
            tokenManager.clearTokens()
            Result.success(Unit)
        } catch (e: Exception) {
            tokenManager.clearTokens()
            Result.success(Unit)
        }
    }

    override val accessToken: Flow<String?> = tokenManager.accessToken
    override val refreshToken: Flow<String?> = tokenManager.refreshToken
    override val userId: Flow<Long?> = tokenManager.userId
    override val username: Flow<String?> = tokenManager.username

    override suspend fun clearSession() {
        tokenManager.clearTokens()
    }
}
