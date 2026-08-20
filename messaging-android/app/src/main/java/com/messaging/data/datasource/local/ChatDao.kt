package com.messaging.data.datasource.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * ChatDao - Data access methods for chats table (synchronous style for compiler robustness)
 */
@Dao
interface ChatDao {
    @Query("SELECT * FROM chats ORDER BY isPinned DESC, lastMessageAt DESC")
    fun getAllChatsFlow(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE id = :chatId")
    fun getChatById(chatId: Long): ChatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertChats(chats: List<ChatEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertChat(chat: ChatEntity)

    @Query("UPDATE chats SET unreadCount = 0 WHERE id = :chatId")
    fun resetUnreadCount(chatId: Long)

    /**
     * Atomically bumps the unread counter and updates the last-message preview in a single SQL
     * statement. The previous approach (read the row, compute unreadCount + 1 in Kotlin, write
     * the whole row back) was a classic read-modify-write race: if two updates to the same chat
     * happened close together (e.g. several messages arriving in quick succession, or a
     * mark-as-read happening concurrently with a new message), one update could silently
     * overwrite the other and the badge would freeze at a stale, too-low count. Doing the
     * increment in SQL (`unreadCount + 1`) makes each update atomic regardless of ordering.
     */
    @Query(
        "UPDATE chats SET lastMessageText = :text, lastMessageSenderId = :senderId, " +
        "lastMessageAt = :at, unreadCount = unreadCount + 1 WHERE id = :chatId"
    )
    fun incrementUnreadAndUpdateLastMessage(chatId: Long, text: String?, senderId: Long?, at: Long)

    @Query(
        "UPDATE chats SET lastMessageText = :text, lastMessageSenderId = :senderId, " +
        "lastMessageAt = :at WHERE id = :chatId"
    )
    fun updateLastMessageOnly(chatId: Long, text: String?, senderId: Long?, at: Long)

    @Query("DELETE FROM chats")
    fun deleteAllChats()
    

   
}
