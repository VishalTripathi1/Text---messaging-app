package com.messaging.data.datasource.remote

import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * TokenAuthenticator
 *
 * Automatically refreshes an expired access token when an authenticated
 * HTTP request receives a 401 Unauthorized response.
 */
class TokenAuthenticator(
    private val tokenRefreshManager: TokenRefreshManager
) : Authenticator {

    override fun authenticate(
        route: Route?,
        response: Response
    ): Request? {

        // Avoid infinite retry loops.
        if (responseCount(response) >= 2) {
            return null
        }

        val failedAccessToken = response.request
            .header("Authorization")
            ?.removePrefix("Bearer ")

        val newAccessToken =
            tokenRefreshManager.refreshForAccessToken(failedAccessToken)
                ?: return null

        return response.request
            .newBuilder()
            .header(
                "Authorization",
                "Bearer $newAccessToken"
            )
            .build()
    }

    private fun responseCount(response: Response): Int {
        var result = 1
        var priorResponse = response.priorResponse

        while (priorResponse != null) {
            result++
            priorResponse = priorResponse.priorResponse
        }

        return result
    }
}