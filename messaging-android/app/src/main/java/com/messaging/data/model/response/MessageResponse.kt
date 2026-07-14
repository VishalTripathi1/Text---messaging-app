package com.messaging.data.model.response

/**
 * MessageResponse - DTO representing a message payload
 */
data class MessageResponse(
    val id: Long,
    val chatId: Long?,
    val groupId: Long?,
    val senderId: Long,
    val senderUsername: String,
    val messageText: String?,
    val messageType: String,
    val isEdited: Boolean,
    val isDeleted: Boolean,
    val createdAt: String,
    val attachmentPath: String? = null,
    val attachmentFileName: String? = null,
    val attachmentFileType: String? = null,
    val attachmentFileSize: Long? = null,
    val reactions: List<ReactionResponse>? = null
)
