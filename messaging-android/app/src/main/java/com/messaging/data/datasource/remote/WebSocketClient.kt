package com.messaging.data.datasource.remote

import android.util.Log
import com.google.gson.Gson
import com.messaging.data.model.WebSocketMessage
import com.messaging.util.TokenManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.firstOrNull
import okhttp3.*
import java.util.concurrent.TimeUnit

/**
 * WebSocketClient - Persistent real-time communication connection client
 */
class WebSocketClient(
    private val okHttpClient: OkHttpClient,
    private val tokenManager: TokenManager,
    private val gson: Gson
) {
    private var webSocket: WebSocket? = null
    private val _incomingMessages = MutableSharedFlow<WebSocketMessage>(extraBufferCapacity = 100)
    val incomingMessages: SharedFlow<WebSocketMessage> = _incomingMessages

    private val _connectionStatus = MutableStateFlow(false)
    val connectionStatus: StateFlow<Boolean> = _connectionStatus

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isClosedIntentionally = false
    private var reconnectDelay = 2000L

    fun connect() {
        isClosedIntentionally = false
        scope.launch {
            if (_connectionStatus.value) return@launch
            val token = tokenManager.accessToken.firstOrNull()
            if (token.isNullOrBlank()) {
                Log.e("WebSocketClient", "Cannot connect to WebSockets: Access token is missing")
                return@launch
            }

            val url = "ws://10.0.2.2:8080/ws?token=$token"
            val request = Request.Builder().url(url).build()

            webSocket = okHttpClient.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    Log.i("WebSocketClient", "WebSocket connection opened successfully")
                    _connectionStatus.value = true
                    reconnectDelay = 2000L
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    Log.d("WebSocketClient", "WebSocket message received: $text")
                    try {
                        val message = gson.fromJson(text, WebSocketMessage::class.java)
                        scope.launch {
                            _incomingMessages.emit(message)
                        }
                    } catch (e: Exception) {
                        Log.e("WebSocketClient", "Error parsing WebSocket message", e)
                    }
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i("WebSocketClient", "WebSocket closing: $code / $reason")
                    webSocket.close(1000, null)
                }

                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                    Log.i("WebSocketClient", "WebSocket closed: $code / $reason")
                    _connectionStatus.value = false
                    triggerReconnect()
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    Log.e("WebSocketClient", "WebSocket failure", t)
                    _connectionStatus.value = false
                    triggerReconnect()
                }
            })
        }
    }

    private fun triggerReconnect() {
        if (isClosedIntentionally) return
        scope.launch {
            delay(reconnectDelay)
            reconnectDelay = (reconnectDelay * 2).coerceAtMost(30000L)
            Log.i("WebSocketClient", "Attempting reconnection in $reconnectDelay ms...")
            connect()
        }
    }

    fun sendMessage(message: WebSocketMessage): Boolean {
        val ws = webSocket
        if (ws != null && _connectionStatus.value) {
            try {
                val json = gson.toJson(message)
                return ws.send(json)
            } catch (e: Exception) {
                Log.e("WebSocketClient", "Failed to send WebSocket message", e)
            }
        } else {
            Log.e("WebSocketClient", "WebSocket is not connected")
        }
        return false
    }

    fun disconnect() {
        isClosedIntentionally = true
        webSocket?.close(1000, "Closed intentionally")
        webSocket = null
        _connectionStatus.value = false
    }
}
