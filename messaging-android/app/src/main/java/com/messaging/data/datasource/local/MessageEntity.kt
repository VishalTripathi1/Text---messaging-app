package com.messaging.data.datasource.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.messaging.domain.entity.Reaction

/**
 * MessageEntity - Room entity representing a message log
 */
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: Long,
    val chatId: Long?,
    val groupId: Long?,
    val senderId: Long,
    val senderUsername: String,
    val messageText: String?,
    val messageType: String,
    val isEdited: Boolean,
    val isDeleted: Boolean,
    val createdAt: Long,
    val deliveryStatus: String, // PENDING, DELIVERED, READ
    val attachmentPath: String? = null,
    val attachmentFileName: String? = null,
    val attachmentFileType: String? = null,
    val attachmentFileSize: Long? = null,
    val reactions: List<Reaction>? = null
)
