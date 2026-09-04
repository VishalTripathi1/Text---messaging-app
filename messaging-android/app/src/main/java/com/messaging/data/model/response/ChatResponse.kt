package com.messaging.data.model.response

/**
 * ChatResponse - DTO representing a chat session details from backend
 */
data class ChatResponse(
    val id: Long,
    val otherUser: UserResponse,
    val lastMessage: MessageResponse?,
    val isPinned: Boolean,
    val isArchived: Boolean,
    val isBlockedByMe: Boolean = false,
    val unreadCount: Int
)
