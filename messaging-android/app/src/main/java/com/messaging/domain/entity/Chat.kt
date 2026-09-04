package com.messaging.domain.entity

/**
 * Chat - Domain entity representing a chat room
 */
data class Chat(
    val id: Long,
    val otherUser: User?,
    val lastMessageText: String?,
    val lastMessageSenderId: Long?,
    val lastMessageStatus: String?,
    val lastMessageAt: Long?,
    val isPinned: Boolean,
    val isArchived: Boolean,
    val unreadCount: Int,
    val isGroup: Boolean = false,
    val groupName: String? = null,
    val groupDescription: String? = null,
    val isBlockedByMe: Boolean = false
)
