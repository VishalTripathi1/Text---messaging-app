package com.messaging.infrastructure.repository;

import com.messaging.domain.entity.ReadReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * ReadReceiptRepository - Data access for ReadReceipt entity
 */
@Repository
public interface ReadReceiptRepository extends JpaRepository<ReadReceipt, Long> {
    Optional<ReadReceipt> findByMessageIdAndUserId(Long messageId, Long userId);

    @Query("SELECT COUNT(m) FROM Message m WHERE m.chat.id = :chatId AND m.sender.id != :userId AND " +
           "NOT EXISTS (SELECT r FROM ReadReceipt r WHERE r.message.id = m.id AND r.user.id = :userId) " +
           "AND m.isDeleted = false")
    int countUnreadMessagesInChat(@Param("chatId") Long chatId, @Param("userId") Long userId);
}
