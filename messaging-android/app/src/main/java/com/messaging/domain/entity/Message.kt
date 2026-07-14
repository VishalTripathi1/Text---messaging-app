package com.messaging.domain.entity

/**
 * Message - Domain entity representing a chat message
 */
data class Message(
    val id: Long,
    val chatId: Long?,
    val groupId: Long?,
    val senderId: Long,
    val senderUsername: String,
    val messageText: String?,
    val messageType: String,
    val isEdited: Boolean,
    val isDeleted: Boolean,
    val createdAt: Long,
    val deliveryStatus: String,
    val attachmentPath: String? = null,
    val attachmentFileName: String? = null,
    val attachmentFileType: String? = null,
    val attachmentFileSize: Long? = null,
    val reactions: List<Reaction>? = null
)
