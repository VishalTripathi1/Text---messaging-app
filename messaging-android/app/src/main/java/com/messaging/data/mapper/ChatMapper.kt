package com.messaging.data.mapper

import android.util.Log
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
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

private val formatter = DateTimeFormatter.ISO_DATE_TIME

/**
 * Converts a server timestamp string to epoch millis.
 *
 * IMPORTANT: The backend serializes `LocalDateTime` (no zone/offset info) and the JVM the
 * server runs on stores/produces those values in UTC. A string like "2026-08-20T10:15:30"
 * therefore represents UTC time, NOT the reader's local time. Previously this used
 * `ZoneId.systemDefault()` which reinterpreted the UTC wall-clock time as if it were already
 * in the device's local zone, shifting every timestamp by the device/server offset (e.g. by
 * 5.5 hours for IST users) - this was the cause of messages showing the wrong send time.
 */
fun String?.toEpochMillis(): Long {
    if (this.isNullOrBlank()) {
        return System.currentTimeMillis()
    }

    return try {
        // Proper offset/zoned timestamp (e.g. "...Z" or "...+05:30") - trust it as-is.
        Instant.parse(this).toEpochMilli()
    } catch (instantException: Exception) {
        try {
            // Naive LocalDateTime from the backend - it represents UTC, not device-local time.
            LocalDateTime
                .parse(this, formatter)
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
        } catch (localDateTimeException: Exception) {
            Log.e("ChatMapper", "Failed to parse server timestamp: $this", localDateTimeException)
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
        lastMessageStatus = lastMessageStatus,
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
    val previewText = lastMessage?.let { message ->
        if (message.messageText.isNullOrBlank() && !message.attachmentPath.isNullOrBlank()) {
            when (message.messageType) {
                "IMAGE" -> "📷 Photo"
                "VIDEO" -> "🎥 Video"
                "VOICE" -> "🎤 Voice message"
                else -> "📎 Document"
            }
        } else {
            message.messageText
        }
    }
    return ChatEntity(
        id = id,
        otherUserId = otherUser.id,
        otherUsername = otherUser.username,
        otherDisplayName = otherUser.displayName,
        otherBio = otherUser.bio,
        otherProfilePictureUrl = otherUser.profilePictureUrl,
        otherStatus = otherUser.status ?: "OFFLINE",
        lastMessageText = previewText,
        lastMessageSenderId = lastMessage?.senderId,
        lastMessageStatus = lastMessage?.deliveryStatus ?: "SENT",
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
        otherBio = null,
        otherProfilePictureUrl = null,
        otherStatus = null,
        lastMessageText = null,
        lastMessageSenderId = null,
        lastMessageStatus = null,
        lastMessageAt = createdAt.toEpochMillis(),
        isPinned = false,
        isArchived = false,
        unreadCount = 0,
        isGroup = true,
        groupName = groupName,
        groupDescription = description
    )
}

/**
 * [deliveryStatus] is required rather than defaulted on purpose. It used to default to
 * "DELIVERED", which meant every message pulled by a sync was drawn with a double tick whether or
 * not it had ever reached anybody - and, worse, hid the real bug: a sync could silently *downgrade*
 * a message the server had told us was READ. Callers now have to say what the status is, and every
 * one of them takes it from the server's own `deliveryStatus` field.
 */
fun MessageResponse.toEntity(deliveryStatus: String): MessageEntity {
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
