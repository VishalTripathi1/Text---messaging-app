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

    /**
     * The conversations most likely to be opened next, in the order the list shows them.
     *
     * Used by ChatRepositoryImpl.prefetchRecentConversations to pull their history into the cache
     * before the user taps in, so opening a conversation for the first time doesn't have to wait on
     * the network.
     */
    @Query("SELECT * FROM chats ORDER BY isPinned DESC, lastMessageAt DESC LIMIT :limit")
    fun getRecentChats(limit: Int): List<ChatEntity>

    @Query("SELECT * FROM chats WHERE id = :chatId")
    fun getChatById(chatId: Long): ChatEntity?

    @Query("SELECT * FROM chats WHERE id = :chatId")
    fun getChatByIdFlow(chatId: Long): Flow<ChatEntity?>

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

    @Query("UPDATE chats SET lastMessageText = 'This message was deleted', lastMessageStatus = NULL WHERE id = :chatId AND lastMessageSenderId = :senderId")
    fun markLastMessageDeleted(chatId: Long, senderId: Long)

    /**
     * Bumps the unread badge without touching the last-message preview.
     *
     * Needed by the edited-message path: an edit makes the message unread again, but the message
     * being edited can sit anywhere in the history. Reusing incrementUnreadAndUpdateLastMessage()
     * would rewrite the chat-list preview with the edited text even when that message is not the
     * newest one, so the list would claim the conversation's last words were something said hours
     * ago. See ChatRepositoryImpl.handleIncomingWebSocketMessage.
     */
    @Query("UPDATE chats SET unreadCount = unreadCount + 1 WHERE id = :chatId")
    fun incrementUnreadCount(chatId: Long)

    @Query("UPDATE chats SET lastMessageStatus = :status WHERE id = :chatId AND lastMessageSenderId = :senderId AND lastMessageText = :text")
    fun updateLastMessageStatus(chatId: Long, senderId: Long, text: String?, status: String)

    @Query("DELETE FROM chats")
    fun deleteAllChats()

    /**
     * Removes cached chats the server no longer lists, so a chat deleted server-side stops showing
     * up locally. syncChats() only ever inserted, which is why chats belonging to deleted accounts
     * lingered as ghost rows reading "No messages yet" forever.
     *
     * Private chats and groups are pruned separately because each comes from its own endpoint - a
     * successful private-chat fetch says nothing about which groups exist, so pruning both from one
     * response would delete the other kind wholesale. Groups are stored under negated ids (see
     * ChatMapper), hence the isGroup discriminator rather than a sign test.
     *
     * There are two methods per kind because Room generates invalid SQL for an empty IN () list;
     * the caller picks the wholesale delete when the server returned nothing. Callers must key the
     * decision on HTTP success, never on the list being empty, or a failed request wipes the cache.
     *
     * Both variants spare any chat that holds local messages, for two reasons. The server hides
     * message-less chats from /chats (see ChatRepository.findUserChatsExcludingArchived), so a chat
     * the user has only just opened is legitimately absent from the response and must not be
     * deleted out from under them; and a conversation with history should never lose that history
     * because a list endpoint stopped mentioning it. The consequence is that a server-side deleted
     * chat that does have local messages survives locally - deliberately, since dropping real
     * message history on the strength of one list response is the worse failure.
     */
    @Query(
        "DELETE FROM chats WHERE isGroup = 0 AND id NOT IN (:serverChatIds) " +
        "AND id NOT IN (SELECT DISTINCT chatId FROM messages WHERE chatId IS NOT NULL)"
    )
    fun deleteStalePrivateChatsNotIn(serverChatIds: List<Long>)

    @Query(
        "DELETE FROM chats WHERE isGroup = 0 " +
        "AND id NOT IN (SELECT DISTINCT chatId FROM messages WHERE chatId IS NOT NULL)"
    )
    fun deleteAllStalePrivateChats()

    @Query(
        "DELETE FROM chats WHERE isGroup = 1 AND id NOT IN (:serverChatIds) " +
        "AND id NOT IN (SELECT DISTINCT -groupId FROM messages WHERE groupId IS NOT NULL)"
    )
    fun deleteStaleGroupChatsNotIn(serverChatIds: List<Long>)

    @Query(
        "DELETE FROM chats WHERE isGroup = 1 " +
        "AND id NOT IN (SELECT DISTINCT -groupId FROM messages WHERE groupId IS NOT NULL)"
    )
    fun deleteAllStaleGroupChats()

    /**
     * Updates the cached online/offline status for whichever chat row has this user as the
     * other participant. The backend already broadcasts USER_STATUS over the WebSocket on every
     * connect/disconnect, but nothing on the client ever handled that message type - so status
     * only ever changed after a manual sync/refresh, not in real time.
     */
    @Query("UPDATE chats SET otherStatus = :status WHERE otherUserId = :userId")
    fun updateOtherUserStatus(userId: Long, status: String)
}
