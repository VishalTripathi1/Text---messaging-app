package com.messaging.data.datasource.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * ChatEntity - Room entity representing a chat room
 */
@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val id: Long,
    val otherUserId: Long?,
    val otherUsername: String?,
    val otherDisplayName: String?,
    val otherBio: String?,
    val otherProfilePictureUrl: String?,
    val otherStatus: String?,
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
