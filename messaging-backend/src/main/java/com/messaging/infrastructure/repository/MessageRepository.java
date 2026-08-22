package com.messaging.infrastructure.repository;

import com.messaging.domain.entity.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * MessageRepository - Data access for Message entity
 */
@Repository
public interface MessageRepository extends JpaRepository<Message, Long> {

    @Query("SELECT m FROM Message m WHERE m.chat.id = :chatId " +
           "ORDER BY m.createdAt DESC")
    Page<Message> findChatMessages(@Param("chatId") Long chatId, Pageable pageable);

    @Query("SELECT m FROM Message m WHERE m.group.id = :groupId " +
           "ORDER BY m.createdAt DESC")
    Page<Message> findGroupMessages(@Param("groupId") Long groupId, Pageable pageable);

    @Query("SELECT m FROM Message m WHERE " +
           "m.isDeleted = false AND LOWER(m.messageText) LIKE LOWER(CONCAT('%', :query, '%')) AND (" +
           "  (m.chat IS NOT NULL AND (m.chat.user1.id = :userId OR m.chat.user2.id = :userId)) " +
           "  OR (m.group IS NOT NULL AND m.group.id IN " +
           "      (SELECT gm.group.id FROM GroupMember gm WHERE gm.user.id = :userId))" +
           ") ORDER BY m.createdAt DESC")
    Page<Message> searchMessages(@Param("userId") Long userId, @Param("query") String query, Pageable pageable);

    @Query("SELECT m FROM Message m WHERE m.chat.id = :chatId AND m.sender.id != :userId AND m.isDeleted = false " +
           "AND NOT EXISTS (SELECT r FROM ReadReceipt r WHERE r.message.id = m.id AND r.user.id = :userId)")
    List<Message> findUnreadMessagesInChat(@Param("chatId") Long chatId, @Param("userId") Long userId);

    long countBySenderId(Long userId);

    /**
     * Authorization check: returns true if the user is a participant of the given chat.
     */
    @Query("SELECT CASE WHEN COUNT(c) > 0 THEN true ELSE false END FROM Chat c " +
           "WHERE c.id = :chatId AND (c.user1.id = :userId OR c.user2.id = :userId)")
    boolean isUserInChat(@Param("chatId") Long chatId, @Param("userId") Long userId);

    /**
     * Authorization check: returns true if the user is a member of the given group.
     */
    @Query("SELECT CASE WHEN COUNT(gm) > 0 THEN true ELSE false END FROM GroupMember gm " +
           "WHERE gm.group.id = :groupId AND gm.user.id = :userId")
    boolean isUserInGroup(@Param("groupId") Long groupId, @Param("userId") Long userId);
}

