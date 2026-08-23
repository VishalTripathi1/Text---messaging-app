package com.messaging.infrastructure.repository;

import com.messaging.domain.entity.Chat;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

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
     * Same as findUserChats but excludes chats the user has archived for themselves. Archive
     * state now lives per-user in ChatUserPreference (a chat that one participant archives no
     * longer disappears for the other participant too, which is what the old global
     * Chat.isArchived flag caused). Using a LEFT JOIN with COALESCE means a chat with no
     * preference row yet (the common case - most chats are never archived) still comes through
     * correctly as "not archived" by default.
     */
    @Query("SELECT c FROM Chat c LEFT JOIN ChatUserPreference p ON p.chat = c AND p.user.id = :userId WHERE " +
           "(c.user1.id = :userId OR c.user2.id = :userId) AND " +
           "COALESCE(p.isArchived, false) = false " +
           "ORDER BY c.lastMessageAt DESC")
    Page<Chat> findUserChatsExcludingArchived(@Param("userId") Long userId, Pageable pageable);

    @Query("SELECT c FROM Chat c JOIN ChatUserPreference p ON p.chat = c WHERE " +
           "(c.user1.id = :userId OR c.user2.id = :userId) AND " +
           "p.user.id = :userId AND p.isArchived = true " +
           "ORDER BY c.lastMessageAt DESC")
    Page<Chat> findArchivedUserChats(@Param("userId") Long userId, Pageable pageable);

    @Query("SELECT c FROM Chat c JOIN ChatUserPreference p ON p.chat = c WHERE " +
           "(c.user1.id = :userId OR c.user2.id = :userId) AND " +
           "p.user.id = :userId AND p.isPinned = true")
    Page<Chat> findPinnedChats(@Param("userId") Long userId, Pageable pageable);
}
