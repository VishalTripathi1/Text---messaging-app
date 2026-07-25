package com.messaging.data.datasource.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * MessageDao - Data access methods for messages table (synchronous style for compiler robustness)
 */
@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAt ASC")
    fun getMessagesForChatFlow(chatId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE groupId = :groupId ORDER BY createdAt ASC")
    fun getMessagesForGroupFlow(groupId: Long): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :messageId")
    fun getMessageById(messageId: Long): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMessages(messages: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMessage(message: MessageEntity)

    @Query("UPDATE messages SET deliveryStatus = :status WHERE id = :messageId")
    fun updateDeliveryStatus(messageId: Long, status: String)

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    fun deleteMessagesForChat(chatId: Long)

    @Query("DELETE FROM messages WHERE id = :id")
    fun deleteMessageById(id: Long)

    @Query("UPDATE messages SET messageText = :newText, isEdited = 1 WHERE id = :messageId")
    fun updateMessageText(messageId: Long, newText: String)

    @Query("UPDATE messages SET isDeleted = 1, messageText = 'This message was deleted' WHERE id = :messageId")
    fun markMessageAsDeleted(messageId: Long)

    @Query("UPDATE messages SET deliveryStatus = 'READ' WHERE chatId = :chatId AND deliveryStatus != 'READ'")
    fun markChatMessagesAsRead(chatId: Long)

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAt DESC LIMIT 1")
    fun getLatestMessageForChat(chatId: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE groupId = :groupId ORDER BY createdAt DESC LIMIT 1")
    fun getLatestMessageForGroup(groupId: Long): MessageEntity?

    @Query("DELETE FROM messages WHERE chatId = :chatId AND id < 0")
    fun deletePendingMessagesForChat(chatId: Long)

    @Query("DELETE FROM messages WHERE groupId = :groupId AND id < 0")
    fun deletePendingMessagesForGroup(groupId: Long)

    @Query("SELECT * FROM messages WHERE chatId = :chatId AND id < 0 ORDER BY createdAt ASC LIMIT 1")
    fun getOldestPendingMessageForChat(chatId: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE groupId = :groupId AND id < 0 ORDER BY createdAt ASC LIMIT 1")
    fun getOldestPendingMessageForGroup(groupId: Long): MessageEntity?

    @Query("DELETE FROM messages")
    fun deleteAllMessages()
}
