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

    @Query(value = "SELECT * FROM messages m WHERE " +
           "to_tsvector('english', m.message_text) @@ plainto_tsquery('english', :query) " +
           "AND m.is_deleted = false " +
           "ORDER BY m.created_at DESC",
           countQuery = "SELECT count(*) FROM messages m WHERE " +
           "to_tsvector('english', m.message_text) @@ plainto_tsquery('english', :query) " +
           "AND m.is_deleted = false",
           nativeQuery = true)
    Page<Message> searchMessages(@Param("query") String query, Pageable pageable);

    long countBySenderId(Long userId);
}
