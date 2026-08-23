package com.messaging.data.datasource.remote

import android.util.Log
import com.google.gson.Gson
import com.messaging.data.model.WebSocketMessage
import com.messaging.util.Constants
import com.messaging.util.TokenManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.Route

/**
 * WebSocketClient
 *
 * Persistent real-time communication connection.
 *
 * Handles:
 *  - WebSocket connection
 *  - automatic reconnect
 *  - expired access-token recovery
 *  - WebSocket 401 handshake failures
 *  - connection state
 */
class WebSocketClient(
    private val okHttpClient: OkHttpClient,
    private val tokenManager: TokenManager,
    private val gson: Gson,
    private val tokenRefreshManager: TokenRefreshManager
) {

    private var webSocket: WebSocket? = null

    private val _incomingMessages =
        MutableSharedFlow<WebSocketMessage>(
            extraBufferCapacity = 100
        )

    val incomingMessages: SharedFlow<WebSocketMessage> =
        _incomingMessages

    private val _connectionStatus =
        MutableStateFlow(false)

    val connectionStatus: StateFlow<Boolean> =
        _connectionStatus

    private val scope =
        CoroutineScope(
            Dispatchers.IO + SupervisorJob()
        )

    @Volatile
    private var isClosedIntentionally = false

    @Volatile
    private var isConnecting = false

    private var reconnectDelay = 2000L

    @Volatile
    private var reconnectScheduled = false

    /**
     * Incremented on every connect attempt. Each listener captures the value current when it was
     * created and ignores its own callbacks once a newer attempt has superseded it.
     *
     * Without this, the close of the previous socket below raced the opening of the new one: the old
     * socket's onClosed would fire after the new socket's onOpen, clear _connectionStatus and
     * isConnecting even though the new socket was perfectly healthy, and schedule a reconnect that
     * closed it again - an endless connect/close flap where the app reports itself offline while
     * holding a working connection.
     */
    private val connectionGeneration = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * @param isRetry true when called from the automatic backoff, false for an explicit connect
     *   (app launch, screen resume, login). An explicit connect resets the backoff, so a user
     *   returning to the app is not made to wait out a 30-second delay inherited from failures that
     *   happened while it was in the background.
     */
    fun connect(isRetry: Boolean = false) {
        isClosedIntentionally = false

        if (!isRetry) {
            reconnectDelay = 2000L
        }

        scope.launch {

            if (
                _connectionStatus.value ||
                isConnecting
            ) {
                return@launch
            }

            isConnecting = true

            // Everything below runs under try/catch so isConnecting cannot be left stranded at
            // true. If it ever were - a DataStore read throwing, newWebSocket() refusing the
            // request - the guard above would reject every future connect attempt for the rest of
            // the process's life, disabling reconnection entirely with no way back except killing
            // the app.
            try {

                val token = tokenManager.accessToken.firstOrNull()

                if (token.isNullOrBlank()) {
                    Log.e(
                        "WebSocketClient",
                        "Cannot connect to WebSockets: Access token is missing"
                    )

                    isConnecting = false
                    return@launch
                }

                val url =
                    "${Constants.WS_BASE_URL}?token=$token"

                Log.d(
                    "WebSocketClient",
                    "Connecting to WebSocket"
                )

                val request =
                    Request.Builder()
                        .url(url)
                        .build()

                val generation = connectionGeneration.incrementAndGet()

                webSocket?.close(
                    1000,
                    "Replacing stale connection"
                )

                webSocket =
                    okHttpClient.newWebSocket(
                        request,
                        object : WebSocketListener() {

                            /** False once a newer connect attempt has replaced this listener. */
                            private fun isCurrent(): Boolean =
                                connectionGeneration.get() == generation

                            override fun onOpen(
                                webSocket: WebSocket,
                                response: Response
                            ) {
                                if (!isCurrent()) {
                                    webSocket.close(1000, "Superseded before open")
                                    return
                                }

                                Log.i(
                                    "WebSocketClient",
                                    "WebSocket connection opened successfully"
                                )

                                _connectionStatus.value = true
                                isConnecting = false
                                reconnectDelay = 2000L
                                reconnectScheduled = false
                            }

                            override fun onMessage(
                                webSocket: WebSocket,
                                text: String
                            ) {
                                if (!isCurrent()) return

                                Log.d(
                                    "WebSocketClient",
                                    "WebSocket message received: $text"
                                )

                                try {
                                    val message =
                                        gson.fromJson(
                                            text,
                                            WebSocketMessage::class.java
                                        )

                                    scope.launch {
                                        _incomingMessages.emit(message)
                                    }

                                } catch (e: Exception) {
                                    Log.e(
                                        "WebSocketClient",
                                        "Error parsing WebSocket message",
                                        e
                                    )
                                }
                            }

                            override fun onClosing(
                                webSocket: WebSocket,
                                code: Int,
                                reason: String
                            ) {
                                Log.i(
                                    "WebSocketClient",
                                    "WebSocket closing: $code / $reason"
                                )

                                webSocket.close(
                                    1000,
                                    null
                                )
                            }

                            override fun onClosed(
                                webSocket: WebSocket,
                                code: Int,
                                reason: String
                            ) {
                                Log.i(
                                    "WebSocketClient",
                                    "WebSocket closed: $code / $reason (current=${isCurrent()})"
                                )

                                if (!isCurrent()) return

                                _connectionStatus.value = false
                                isConnecting = false

                                triggerReconnect()
                            }

                            override fun onFailure(
                                webSocket: WebSocket,
                                t: Throwable,
                                response: Response?
                            ) {
                                val responseCode =
                                    response?.code

                                Log.e(
                                    "WebSocketClient",
                                    "WebSocket failure. HTTP code: $responseCode (current=${isCurrent()})",
                                    t
                                )

                                if (!isCurrent()) return

                                _connectionStatus.value = false
                                isConnecting = false

                                /*
                                 * WebSocket authentication is separate from normal
                                 * Retrofit requests: OkHttp's Authenticator, which refreshes the
                                 * token for HTTP calls, is never consulted for a handshake. So the
                                 * handshake has to recognise an auth failure itself, refresh, and
                                 * reconnect with the new token.
                                 *
                                 * 403 is accepted alongside 401 because a Spring Security chain
                                 * with no configured entry point answers an expired token with 403,
                                 * and a proxy in front of the server may do the same. Treating only
                                 * 401 as "re-authenticate" meant any such deployment reconnected
                                 * forever with a token that could never work.
                                 */
                                if (responseCode == 401 || responseCode == 403) {
                                    scope.launch {

                                        if (isClosedIntentionally) {
                                            return@launch
                                        }

                                        Log.i(
                                            "WebSocketClient",
                                            "WebSocket handshake rejected with $responseCode. Refreshing access token..."
                                        )

                                        val refreshedToken =
                                            tokenRefreshManager
                                                .refreshForAccessToken(token)

                                        if (
                                            !refreshedToken.isNullOrBlank()
                                        ) {
                                            Log.i(
                                                "WebSocketClient",
                                                "Token refreshed. Reconnecting WebSocket..."
                                            )

                                            reconnectDelay = 2000L
                                            reconnectScheduled = false

                                            connect()
                                        } else {
                                            Log.e(
                                                "WebSocketClient",
                                                "Unable to refresh token for WebSocket"
                                            )

                                            triggerReconnect()
                                        }
                                    }

                                } else {
                                    triggerReconnect()
                                }
                            }
                        }
                    )

            } catch (t: Throwable) {
                Log.e(
                    "WebSocketClient",
                    "WebSocket connect attempt threw before the socket was established",
                    t
                )

                isConnecting = false
                _connectionStatus.value = false

                triggerReconnect()
            }
        }
    }

    private fun triggerReconnect() {

        if (isClosedIntentionally) {
            return
        }

        synchronized(this) {

            if (reconnectScheduled) {
                return
            }

            reconnectScheduled = true
        }

        scope.launch {

            delay(reconnectDelay)

            if (isClosedIntentionally) {
                reconnectScheduled = false
                return@launch
            }

            reconnectDelay =
                (reconnectDelay * 2)
                    .coerceAtMost(30000L)

            reconnectScheduled = false

            Log.i(
                "WebSocketClient",
                "Attempting WebSocket reconnection..."
            )

            connect(isRetry = true)
        }
    }

    fun sendMessage(
        message: WebSocketMessage
    ): Boolean {

        val ws = webSocket

        if (
            ws != null &&
            _connectionStatus.value
        ) {

            return try {

                val json =
                    gson.toJson(message)

                ws.send(json)

            } catch (e: Exception) {

                Log.e(
                    "WebSocketClient",
                    "Failed to send WebSocket message",
                    e
                )

                false
            }

        } else {

            Log.e(
                "WebSocketClient",
                "WebSocket is not connected"
            )

            return false
        }
    }

    fun disconnect() {

        isClosedIntentionally = true
        reconnectScheduled = false
        isConnecting = false

        // Retire the current listener so the close below cannot report itself as a dropped
        // connection and schedule a reconnect we explicitly do not want.
        connectionGeneration.incrementAndGet()

        webSocket?.close(
            1000,
            "Closed intentionally"
        )

        webSocket = null

        _connectionStatus.value = false
    }
}