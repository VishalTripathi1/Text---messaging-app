package com.messaging.infrastructure.repository;

import com.messaging.domain.entity.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * MessageRepository - Data access for Message entity
 */
@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {

    @Query("SELECT m FROM Message m WHERE m.chat.id = :chatId AND m.isDeleted = false " +
           "ORDER BY m.createdAt DESC")
    Page<Message> findChatMessages(@Param("chatId") Long chatId, Pageable pageable);

    @Query("SELECT m FROM Message m WHERE m.group.id = :groupId AND m.isDeleted = false " +
           "ORDER BY m.createdAt DESC")
    Page<Message> findGroupMessages(@Param("groupId") Long groupId, Pageable pageable);

    @Query("SELECT m FROM Message m WHERE " +
           "m.isDeleted = false AND LOWER(m.messageText) LIKE LOWER(CONCAT('%', :query, '%')) AND (" +
           "  (m.chat IS NOT NULL AND (m.chat.user1.id = :userId OR m.chat.user2.id = :userId)) " +
           "  OR (m.group IS NOT NULL AND m.group.id IN " +
           "      (SELECT gm.group.id FROM GroupMember gm WHERE gm.user.id = :userId))" +
           ") ORDER BY m.createdAt DESC")
    Page<Message> searchMessages(@Param("userId") Long userId, @Param("query") String query, Pageable pageable);

    long countBySenderId(Long userId);
}
