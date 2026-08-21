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

    fun connect() {
        isClosedIntentionally = false

        scope.launch {

            if (
                _connectionStatus.value ||
                isConnecting
            ) {
                return@launch
            }

            isConnecting = true

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

            webSocket?.close(
                1000,
                "Replacing stale connection"
            )

            webSocket =
                okHttpClient.newWebSocket(
                    request,
                    object : WebSocketListener() {

                        override fun onOpen(
                            webSocket: WebSocket,
                            response: Response
                        ) {
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
                                "WebSocket closed: $code / $reason"
                            )

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
                                "WebSocket failure. HTTP code: $responseCode",
                                t
                            )

                            _connectionStatus.value = false
                            isConnecting = false

                            /*
                             * WebSocket authentication is separate from normal
                             * Retrofit requests. If the handshake failed because
                             * the access token expired, refresh it and reconnect
                             * using the new token.
                             */
                            if (responseCode == 401) {
                                scope.launch {

                                    if (isClosedIntentionally) {
                                        return@launch
                                    }

                                    Log.i(
                                        "WebSocketClient",
                                        "WebSocket received 401. Refreshing access token..."
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

            connect()
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

        webSocket?.close(
            1000,
            "Closed intentionally"
        )

        webSocket = null

        _connectionStatus.value = false
    }
}