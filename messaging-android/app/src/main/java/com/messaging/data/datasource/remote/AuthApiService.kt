package com.messaging.data.datasource.remote

import com.messaging.data.model.request.LoginRequest
import com.messaging.data.model.request.RegisterRequest
import com.messaging.data.model.response.AuthResponse
import retrofit2.Response
import retrofit2.http.*

/**
 * AuthApiService - Retrofit endpoints for authentication
 */
interface AuthApiService {

    @POST("v1/auth/register")
    suspend fun register(
        @Body request: RegisterRequest
    ): Response<AuthResponse>

    @POST("v1/auth/login")
    suspend fun login(
        @Body request: LoginRequest
    ): Response<AuthResponse>

    @POST("v1/auth/refresh-token")
    suspend fun refreshToken(
        @Query("refreshToken") refreshToken: String
    ): Response<AuthResponse>

    @POST("v1/auth/validate-token")
    suspend fun validateToken(
        @Query("token") token: String
    ): Response<Boolean>

    @POST("v1/auth/logout")
    suspend fun logout(
        @Header("Authorization") authHeader: String
    ): Response<Unit>
}
