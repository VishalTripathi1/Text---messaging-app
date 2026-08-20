package com.messaging.data.datasource.remote

import android.util.Log
import com.google.gson.Gson
import com.messaging.data.model.response.AuthResponse
import com.messaging.util.TokenManager
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route

/**
 * TokenAuthenticator - Automatically refreshes an expired access token and retries the
 * original request.
 *
 * Previously, once the (default 1-hour) access token expired, every authenticated call -
 * starting a new chat, reacting to a message, pinning/archiving, editing, searching, etc. -
 * would silently fail with 401 Unauthorized. The app *did* store a refresh token and had a
 * working /v1/auth/refresh-token endpoint, but nothing ever called it, so users had to log
 * out and back in to recover. This class plugs that gap in.
 */
class TokenAuthenticator(
    private val tokenManager: TokenManager,
    private val plainOkHttpClient: () -> OkHttpClient
) : Authenticator {

    private val lock = Any()

    override fun authenticate(route: Route?, response: Response): Request? {
        // Avoid infinite retry loops.
        if (responseCount(response) >= 2) return null

        val newAccessToken = synchronized(lock) {
            runBlocking {
                val currentToken = tokenManager.accessToken.firstOrNull()
                val failedToken = response.request.header("Authorization")?.removePrefix("Bearer ")

                // Another thread already refreshed while we were waiting on the lock.
                if (currentToken != null && failedToken != null && currentToken != failedToken) {
                    return@runBlocking currentToken
                }

                val refreshToken = tokenManager.refreshToken.firstOrNull()
                if (refreshToken.isNullOrBlank()) {
                    return@runBlocking null
                }

                try {
                    val client = plainOkHttpClient()
                    val url = com.messaging.util.Constants.BASE_URL + "v1/auth/refresh-token?refreshToken=$refreshToken"
                    val request = Request.Builder()
                        .url(url)
                        .post("".toRequestBody(null))
                        .build()
                    val refreshResponse = client.newCall(request).execute()
                    refreshResponse.use {
                        if (!it.isSuccessful) return@runBlocking null
                        val body = it.body?.string() ?: return@runBlocking null
                        val auth = Gson().fromJson(body, AuthResponse::class.java)
                        tokenManager.saveTokens(
                            auth.accessToken,
                            auth.refreshToken,
                            auth.userId,
                            auth.username
                        )
                        auth.accessToken
                    }
                } catch (e: Exception) {
                    Log.e("TokenAuthenticator", "Failed to refresh token", e)
                    null
                }
            }
        }

        return if (newAccessToken != null) {
            response.request.newBuilder()
                .header("Authorization", "Bearer $newAccessToken")
                .build()
        } else {
            null
        }
    }

    private fun responseCount(response: Response): Int {
        var result = 1
        var prior = response.priorResponse
        while (prior != null) {
            result++
            prior = prior.priorResponse
        }
        return result
    }
}
