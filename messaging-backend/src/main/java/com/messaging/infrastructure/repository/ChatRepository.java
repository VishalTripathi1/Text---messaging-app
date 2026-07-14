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
           "(c.user1.id = :userId OR c.user2.id = :userId) AND " +
           "c.isArchived = false " +
           "ORDER BY c.lastMessageAt DESC")
    Page<Chat> findUserChats(@Param("userId") Long userId, Pageable pageable);

    @Query("SELECT c FROM Chat c WHERE " +
           "(c.user1.id = :userId OR c.user2.id = :userId) AND " +
           "c.isPinned = true")
    Page<Chat> findPinnedChats(@Param("userId") Long userId, Pageable pageable);
}
