package com.messaging.data.mapper

import com.messaging.data.datasource.local.ChatEntity
import com.messaging.data.datasource.local.MessageEntity
import com.messaging.data.model.response.ChatResponse
import com.messaging.data.model.response.GroupResponse
import com.messaging.data.model.response.MessageResponse
import com.messaging.data.model.response.UserResponse
import com.messaging.domain.entity.Chat
import com.messaging.domain.entity.Message
import com.messaging.domain.entity.Reaction
import com.messaging.domain.entity.User
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val formatter = DateTimeFormatter.ISO_DATE_TIME

fun String?.toEpochMillis(): Long {
    if (this.isNullOrBlank()) return System.currentTimeMillis()
    return try {
        Instant.parse(this).toEpochMilli()
    } catch (e: Exception) {
        try {
            LocalDateTime.parse(this, formatter)
                .atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        } catch (e2: Exception) {
            System.currentTimeMillis()
        }
    }
}

fun UserResponse.toDomain(): User {
    return User(
        id = id,
        username = username,
        email = email,
        displayName = displayName,
        profilePictureUrl = profilePictureUrl,
        bio = bio,
        status = status ?: "OFFLINE",
        lastSeen = lastSeen
    )
}

fun ChatEntity.toDomain(): Chat {
    val user = if (isGroup) {
        null
    } else {
        User(
            id = otherUserId ?: 0,
            username = otherUsername ?: "",
            email = "",
            displayName = otherDisplayName ?: "",
            profilePictureUrl = otherProfilePictureUrl,
            bio = null,
            status = otherStatus ?: "OFFLINE",
            lastSeen = null
        )
    }
    return Chat(
        id = id,
        otherUser = user,
        lastMessageText = lastMessageText,
        lastMessageSenderId = lastMessageSenderId,
        lastMessageAt = lastMessageAt,
        isPinned = isPinned,
        isArchived = isArchived,
        unreadCount = unreadCount,
        isGroup = isGroup,
        groupName = groupName,
        groupDescription = groupDescription
    )
}

fun MessageEntity.toDomain(): Message {
    return Message(
        id = id,
        chatId = chatId,
        groupId = groupId,
        senderId = senderId,
        senderUsername = senderUsername,
        messageText = messageText,
        messageType = messageType,
        isEdited = isEdited,
        isDeleted = isDeleted,
        createdAt = createdAt,
        deliveryStatus = deliveryStatus,
        attachmentPath = attachmentPath,
        attachmentFileName = attachmentFileName,
        attachmentFileType = attachmentFileType,
        attachmentFileSize = attachmentFileSize,
        reactions = reactions
    )
}

fun ChatResponse.toEntity(): ChatEntity {
    return ChatEntity(
        id = id,
        otherUserId = otherUser.id,
        otherUsername = otherUser.username,
        otherDisplayName = otherUser.displayName,
        otherProfilePictureUrl = otherUser.profilePictureUrl,
        otherStatus = otherUser.status ?: "OFFLINE",
        lastMessageText = lastMessage?.messageText,
        lastMessageSenderId = lastMessage?.senderId,
        lastMessageAt = lastMessage?.createdAt?.toEpochMillis(),
        isPinned = isPinned,
        isArchived = isArchived,
        unreadCount = unreadCount,
        isGroup = false,
        groupName = null,
        groupDescription = null
    )
}

fun GroupResponse.toEntity(): ChatEntity {
    return ChatEntity(
        id = -id,
        otherUserId = null,
        otherUsername = null,
        otherDisplayName = null,
        otherProfilePictureUrl = null,
        otherStatus = null,
        lastMessageText = null,
        lastMessageSenderId = null,
        lastMessageAt = createdAt.toEpochMillis(),
        isPinned = false,
        isArchived = false,
        unreadCount = 0,
        isGroup = true,
        groupName = groupName,
        groupDescription = description
    )
}

fun MessageResponse.toEntity(deliveryStatus: String = "DELIVERED"): MessageEntity {
    val mappedReactions = reactions?.map {
        Reaction(
            userId = it.userId,
            username = it.username,
            reaction = it.reaction
        )
    }
    return MessageEntity(
        id = id,
        chatId = chatId,
        groupId = groupId,
        senderId = senderId,
        senderUsername = senderUsername,
        messageText = messageText,
        messageType = messageType,
        isEdited = isEdited,
        isDeleted = isDeleted,
        createdAt = createdAt.toEpochMillis(),
        deliveryStatus = deliveryStatus,
        attachmentPath = attachmentPath,
        attachmentFileName = attachmentFileName,
        attachmentFileType = attachmentFileType,
        attachmentFileSize = attachmentFileSize,
        reactions = mappedReactions
    )
}
