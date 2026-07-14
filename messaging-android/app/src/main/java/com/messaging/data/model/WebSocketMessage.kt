package com.messaging.data.model

/**
 * WebSocketMessage - Client-side DTO representing real-time communication events
 */
data class WebSocketMessage(
    val type: String, // CHAT_MESSAGE, TYPING, READ_RECEIPT, DELIVERY_STATUS, USER_STATUS
    val senderId: Long? = null,
    val recipientId: Long? = null,
    val chatId: Long? = null,
    val groupId: Long? = null,
    val payload: Any? = null
)
