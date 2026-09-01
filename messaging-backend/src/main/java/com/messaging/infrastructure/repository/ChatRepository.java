package com.messaging.infrastructure.repository;

import com.messaging.domain.entity.Chat;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * ChatRepository - Data access for Chat entity
 */
@Repository
public interface ChatRepository extends JpaRepository<Chat, Long> {

    @Query("SELECT c FROM Chat c WHERE " +
           "(c.user1.id = :userId1 AND c.user2.id = :userId2) OR " +
           "(c.user1.id = :userId2 AND c.user2.id = :userId1)")
    Optional<Chat> findChatBetweenUsers(@Param("userId1") Long userId1, @Param("userId2") Long userId2);

    /**
     * Both participants of several chats, as {@code [chatId, user1Id, user2Id]} rows.
     *
     * <p>{@code user1} and {@code user2} are lazy associations, so working out which participant is
     * "the other one" by calling {@code chat.getUser1().getId()} initialises two proxies per chat -
     * two extra queries per row of the chat list. Selecting {@code c.user1.id} instead reads the
     * foreign-key column that is already on the chat row, with no join and no proxy. See
     * MessageServiceImpl.mapToChatResponses.
     */
    @Query("SELECT c.id, c.user1.id, c.user2.id FROM Chat c WHERE c.id IN :chatIds")
    List<Object[]> findParticipantIdRowsByChatIds(@Param("chatIds") Collection<Long> chatIds);

    /**
     * Claims the single chat row for a user pair, doing nothing if another transaction already
     * holds it. Callers must pass the IDs already ordered (lower first) and re-read the chat with
     * {@link #findChatBetweenUsers} afterwards - see getOrCreateChat in MessageServiceImpl.
     *
     * <p>ON CONFLICT DO NOTHING resolves against the unique index on
     * {@code (LEAST(user_id_1, user_id_2), GREATEST(user_id_1, user_id_2))}, which is what makes
     * the pair unique regardless of which column each ID landed in. The index is a functional
     * one, so it cannot be declared on the Chat entity - JPA's uniqueConstraints only accepts
     * plain column lists, and ddl-auto=update will never create it. It has to exist in the
     * database before this method can dedupe anything (see V1_0_0__InitialSchema.sql).
     *
     * <p>Native rather than a save() wrapped in try/catch, because Postgres aborts the whole
     * transaction on a constraint violation: every later statement in it fails with
     * "current transaction is aborted", so the recovering re-read in a catch block could not run.
     * created_at/updated_at are mapped non-null with no database default, so they are set here
     * rather than left to Hibernate's @CreationTimestamp, which this insert bypasses.
     */
    @Modifying
    @Query(value = "INSERT INTO chats (user_id_1, user_id_2, created_at, updated_at) " +
                   "VALUES (:lowerUserId, :higherUserId, now(), now()) " +
                   "ON CONFLICT DO NOTHING",
           nativeQuery = true)
    void insertIfAbsent(@Param("lowerUserId") Long lowerUserId, @Param("higherUserId") Long higherUserId);

    @Query("SELECT c FROM Chat c WHERE " +
           "(c.user1.id = :userId OR c.user2.id = :userId) " +
           "ORDER BY c.lastMessageAt DESC")
    Page<Chat> findUserChats(@Param("userId") Long userId, Pageable pageable);

    /**
     * Same as findUserChats but excludes chats the user has archived, cleared, or deleted for themselves, and chats where
     * nothing has been said yet. Preference state now lives per-user in ChatUserPreference (a chat that
     * one participant archives/clears/deletes no longer affects the other participant too). Using a LEFT JOIN with COALESCE means a chat with no
     * preference row yet (the common case - most chats are never archived/cleared/deleted) still comes through
     * correctly as "not archived/cleared/deleted" by default.
     *
     * <p>The EXISTS clause keeps empty chats out of the list. Tapping a contact in the new-chat
     * screen calls getOrCreateChat, which inserts the row immediately, so without this filter every
     * browsed contact would pile up in the chat list showing "No messages yet". The row is still
     * created - tapping the same contact again returns the same chat - it just stays hidden until
     * there is something in it.
     *
     * <p>Deliberately tests for a real message rather than {@code c.lastMessageId IS NOT NULL}.
     * Those two agree for any chat this app created, but mapToChatResponse repairs a missing
     * lastMessageId by looking up the latest message, and filtering on that column would hide such
     * a chat from the list before the repair could ever run - permanently. Soft-deleted messages
     * are counted on purpose: deleting the last message in a conversation should not make the whole
     * conversation vanish. Backed by the idx_chat index on messages (chat_id, created_at).
     */
    @Query("SELECT c FROM Chat c LEFT JOIN ChatUserPreference p ON p.chatId = c.id AND p.user.id = :userId WHERE " +
           "(c.user1.id = :userId OR c.user2.id = :userId) AND " +
           "COALESCE(p.isArchived, false) = false AND " +
           "COALESCE(p.isCleared, false) = false AND " +
           "COALESCE(p.deleted, false) = false AND " +
           "EXISTS (SELECT 1 FROM Message m WHERE m.chat = c) " +
           "ORDER BY c.lastMessageAt DESC")
    Page<Chat> findUserChatsExcludingArchived(@Param("userId") Long userId, Pageable pageable);

    @Query("SELECT c FROM Chat c JOIN ChatUserPreference p ON p.chatId = c.id WHERE " +
           "(c.user1.id = :userId OR c.user2.id = :userId) AND " +
           "p.user.id = :userId AND p.isArchived = true " +
           "ORDER BY c.lastMessageAt DESC")
    Page<Chat> findArchivedUserChats(@Param("userId") Long userId, Pageable pageable);

    @Query("SELECT c FROM Chat c JOIN ChatUserPreference p ON p.chatId = c.id WHERE " +
           "(c.user1.id = :userId OR c.user2.id = :userId) AND " +
           "p.user.id = :userId AND p.isCleared = true " +
           "ORDER BY c.lastMessageAt DESC")
    Page<Chat> findClearedUserChats(@Param("userId") Long userId, Pageable pageable);

    @Query("SELECT c FROM Chat c JOIN ChatUserPreference p ON p.chatId = c.id WHERE " +
           "(c.user1.id = :userId OR c.user2.id = :userId) AND " +
           "p.user.id = :userId AND p.deleted = true " +
           "ORDER BY c.lastMessageAt DESC")
    Page<Chat> findDeletedUserChats(@Param("userId") Long userId, Pageable pageable);

    @Query("SELECT c FROM Chat c JOIN ChatUserPreference p ON p.chatId = c.id WHERE " +
           "(c.user1.id = :userId OR c.user2.id = :userId) AND " +
           "p.user.id = :userId AND p.isPinned = true")
    Page<Chat> findPinnedChats(@Param("userId") Long userId, Pageable pageable);
}
