package com.messaging.data.model.request

/**
 * MessageRequest - DTO for sending messages to backend
 */
data class MessageRequest(
    val chatId: Long?,
    val groupId: Long?,
    val recipientId: Long?,
    val messageType: String,
    val messageText: String?,
    val attachmentPath: String? = null,
    val attachmentFileName: String? = null,
    val attachmentFileType: String? = null,
    val attachmentFileSize: Long? = null
)
