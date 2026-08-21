package com.messaging.data.datasource.remote

import android.util.Log
import com.google.gson.Gson
import com.messaging.data.model.response.AuthResponse
import com.messaging.util.Constants
import com.messaging.util.TokenManager
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * TokenRefreshManager
 *
 * Performs access-token refresh using the stored refresh token.
 *
 * This is shared by:
 *  - normal HTTP authentication
 *  - WebSocket authentication
 *
 * A single lock prevents multiple simultaneous refresh requests from
 * rotating the refresh token at the same time.
 */
class TokenRefreshManager(
    private val tokenManager: TokenManager,
    private val plainOkHttpClient: OkHttpClient,
    private val gson: Gson
) {

    private val lock = Any()

    /**
     * Refresh the token after a request failed because of the supplied
     * access token.
     *
     * If another request already refreshed the token while we were waiting
     * for the lock, the newly stored token is returned instead of refreshing
     * again.
     */
    fun refreshForAccessToken(failedAccessToken: String?): String? {
        synchronized(lock) {
            return runBlocking {
                try {
                    val currentAccessToken = tokenManager.accessToken.firstOrNull()

                    // Another request/thread already refreshed the token.
                    if (
                        !failedAccessToken.isNullOrBlank() &&
                        !currentAccessToken.isNullOrBlank() &&
                        currentAccessToken != failedAccessToken
                    ) {
                        Log.d(
                            "TokenRefreshManager",
                            "Access token was already refreshed by another request"
                        )
                        return@runBlocking currentAccessToken
                    }

                    val refreshToken = tokenManager.refreshToken.firstOrNull()

                    if (refreshToken.isNullOrBlank()) {
                        Log.e(
                            "TokenRefreshManager",
                            "No refresh token available"
                        )
                        return@runBlocking null
                    }

                    val url = Constants.BASE_URL
                        .toHttpUrl()
                        .newBuilder()
                        .addPathSegments("v1/auth/refresh-token")
                        .addQueryParameter("refreshToken", refreshToken)
                        .build()

                    Log.d(
                        "TokenRefreshManager",
                        "Refreshing access token"
                    )

                    val request = Request.Builder()
                        .url(url)
                        .post("".toRequestBody(null))
                        .build()

                    plainOkHttpClient
                        .newCall(request)
                        .execute()
                        .use { response ->

                            if (!response.isSuccessful) {
                                Log.e(
                                    "TokenRefreshManager",
                                    "Refresh request failed: HTTP ${response.code}"
                                )
                                return@runBlocking null
                            }

                            val body = response.body?.string()

                            if (body.isNullOrBlank()) {
                                Log.e(
                                    "TokenRefreshManager",
                                    "Refresh response body is empty"
                                )
                                return@runBlocking null
                            }

                            val authResponse = gson.fromJson(
                                body,
                                AuthResponse::class.java
                            )

                            if (authResponse.accessToken.isNullOrBlank()) {
                                Log.e(
                                    "TokenRefreshManager",
                                    "Refresh response did not contain an access token"
                                )
                                return@runBlocking null
                            }

                            if (authResponse.refreshToken.isNullOrBlank()) {
                                Log.e(
                                    "TokenRefreshManager",
                                    "Refresh response did not contain a refresh token"
                                )
                                return@runBlocking null
                            }

                            tokenManager.saveTokens(
                                authResponse.accessToken,
                                authResponse.refreshToken,
                                authResponse.userId,
                                authResponse.username
                            )

                            Log.i(
                                "TokenRefreshManager",
                                "Access token refreshed successfully"
                            )

                            authResponse.accessToken
                        }

                } catch (e: Exception) {
                    Log.e(
                        "TokenRefreshManager",
                        "Failed to refresh token",
                        e
                    )
                    null
                }
            }
        }
    }

    /**
     * Force a refresh.
     *
     * Used primarily by WebSocket when its handshake receives 401.
     */
    fun forceRefresh(): String? {
        return refreshForAccessToken(null)
    }
}