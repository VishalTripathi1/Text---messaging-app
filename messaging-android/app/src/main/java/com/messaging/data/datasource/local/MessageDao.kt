package com.messaging.data.datasource.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

    /**
     * The status we already hold for a message, or null if we don't have it at all.
     *
     * Used to keep delivery status monotonic across the several independent writers that all
     * REPLACE the same row - see ChatRepositoryImpl.keepBestStatus().
     */
    @Query("SELECT deliveryStatus FROM messages WHERE id = :messageId")
    fun getDeliveryStatus(messageId: Long): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMessages(messages: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertMessage(message: MessageEntity)

    /**
     * Swaps the optimistic temp row (negative id) for the server-confirmed one atomically.
     *
     * Previously the caller did deleteMessageById() then insertMessage() as two separate calls.
     * getMessagesForChatFlow() is a Room Flow query, which re-emits on every write it observes -
     * the delete alone was enough to trigger an emission with the message momentarily missing
     * from the list, and the following insert triggered a second emission bringing it back. On a
     * slower recomposition/frame, that showed up exactly as "the sent message disappears, then
     * comes back" even though nothing was ever actually lost from the database. @Transaction
     * makes Room treat both writes as one unit, so observers only ever see the before/after
     * states, never the momentary "deleted" gap in between.
     */
    @Transaction
    suspend fun replaceMessage(tempId: Long, newEntity: MessageEntity) {
        deleteMessageById(tempId)
        insertMessage(newEntity)
    }

    @Query("UPDATE messages SET deliveryStatus = :status WHERE id = :messageId")
    fun updateDeliveryStatus(messageId: Long, status: String)

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    fun deleteMessagesForChat(chatId: Long)

    @Query("DELETE FROM messages WHERE groupId = :groupId")
    fun deleteMessagesForGroup(groupId: Long)

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

    /**
     * Clears the optimistic local twin of a message once the server's own copy arrives.
     *
     * Restricted to PENDING rows on purpose. It used to delete every negative id in the chat, which
     * meant a message whose send had failed was silently destroyed the moment any *later* message in
     * the same conversation succeeded - the row queued for retry vanished and that message was lost
     * with no trace. FAILED rows now survive until flushUnsentMessages() either resends them or
     * recognises them as already stored.
     */
    @Query("DELETE FROM messages WHERE chatId = :chatId AND id < 0 AND deliveryStatus = 'PENDING'")
    fun deletePendingMessagesForChat(chatId: Long)

    @Query("DELETE FROM messages WHERE groupId = :groupId AND id < 0 AND deliveryStatus = 'PENDING'")
    fun deletePendingMessagesForGroup(groupId: Long)

    /**
     * Same reasoning as replaceMessage() above, for the WebSocket echo path: this used to run
     * deletePendingMessagesForChat/Group() and insertMessage() as two separate calls, which could
     * leave the chat's Flow observers seeing the message missing for a moment between the two.
     */
    @Transaction
    suspend fun replacePendingWithConfirmedForChat(chatId: Long, newEntity: MessageEntity) {
        deletePendingMessagesForChat(chatId)
        insertMessage(newEntity)
    }

    @Transaction
    suspend fun replacePendingWithConfirmedForGroup(groupId: Long, newEntity: MessageEntity) {
        deletePendingMessagesForGroup(groupId)
        insertMessage(newEntity)
    }

    @Query("SELECT * FROM messages WHERE chatId = :chatId AND id < 0 ORDER BY createdAt ASC LIMIT 1")
    fun getOldestPendingMessageForChat(chatId: Long): MessageEntity?

    @Query("SELECT * FROM messages WHERE groupId = :groupId AND id < 0 ORDER BY createdAt ASC LIMIT 1")
    fun getOldestPendingMessageForGroup(groupId: Long): MessageEntity?

    @Query("DELETE FROM messages")
    fun deleteAllMessages()

    /**
     * Locally-created messages that never made it to the server, oldest first.
     *
     * A negative id means the row was created by sendMessage() and has no server id yet, so these
     * are exactly the sends that failed or timed out. Used to flush them once the connection comes
     * back - see ChatRepositoryImpl.flushUnsentMessages().
     */
    @Query(
        "SELECT * FROM messages WHERE id < 0 AND deliveryStatus IN ('PENDING', 'FAILED') " +
            "ORDER BY createdAt ASC"
    )
    fun getUnsentMessages(): List<MessageEntity>

    /**
     * Server-side messages in a chat sent by :senderId within a time window, used to decide whether
     * an unsent local row is actually a duplicate before resending it. See flushUnsentMessages().
     */
    @Query(
        "SELECT * FROM messages WHERE id > 0 AND senderId = :senderId AND messageText = :text " +
            "AND ((:chatId IS NOT NULL AND chatId = :chatId) OR (:groupId IS NOT NULL AND groupId = :groupId)) " +
            "AND createdAt BETWEEN :fromMillis AND :toMillis LIMIT 1"
    )
    fun findServerMessageMatching(
        chatId: Long?,
        groupId: Long?,
        senderId: Long,
        text: String,
        fromMillis: Long,
        toMillis: Long
    ): MessageEntity?
}
