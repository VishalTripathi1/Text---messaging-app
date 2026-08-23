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

    /**
     * Counts messages in a chat that someone else sent and this user has no read receipt for.
     *
     * <p>Inequality is written {@code <>} rather than {@code !=} on purpose. Spring Data JPA 3.1
     * parses @Query JPQL with its own ANTLR grammar before Hibernate ever sees it, and that
     * grammar follows the JPQL spec, which only defines {@code <>}. Hibernate's HQL accepts
     * {@code !=} as a vendor extension, so this query worked on older Boot versions and then
     * started throwing BadJpqlGrammarException ("token recognition error at: '!'") at runtime -
     * at every call site, since the parse happens per invocation, not at startup.
     */
    @Query("SELECT COUNT(m) FROM Message m WHERE m.chat.id = :chatId AND m.sender.id <> :userId AND " +
           "NOT EXISTS (SELECT r FROM ReadReceipt r WHERE r.message.id = m.id AND r.user.id = :userId) " +
           "AND m.isDeleted = false")
    int countUnreadMessagesInChat(@Param("chatId") Long chatId, @Param("userId") Long userId);
}
