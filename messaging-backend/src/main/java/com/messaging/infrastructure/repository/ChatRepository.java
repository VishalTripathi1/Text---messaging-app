package com.messaging.infrastructure.repository;

import com.messaging.domain.entity.Chat;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
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

    @Query("SELECT c FROM Chat c WHERE " +
           "(c.user1.id = :userId OR c.user2.id = :userId) AND " +
           "c.isPinned = true")
    Page<Chat> findPinnedChats(@Param("userId") Long userId, Pageable pageable);
}
