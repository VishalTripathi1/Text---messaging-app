package com.messaging.infrastructure.repository;

import com.messaging.domain.entity.ReadReceipt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
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

    /**
     * The same count as {@link #countUnreadMessagesInChat} for several chats at once, as
     * {@code [chatId, count]} rows.
     *
     * <p>Chats with nothing unread are absent from the result rather than present with a zero -
     * {@code GROUP BY} only produces rows for messages that matched - so callers must default a
     * missing chat to 0. Loading the chat list used to run the single-chat count once per chat; see
     * MessageServiceImpl.mapToChatResponses.
     */
    @Query("SELECT m.chat.id, COUNT(m) FROM Message m WHERE m.chat.id IN :chatIds AND m.sender.id <> :userId AND " +
           "NOT EXISTS (SELECT r FROM ReadReceipt r WHERE r.message.id = m.id AND r.user.id = :userId) " +
           "AND m.isDeleted = false GROUP BY m.chat.id")
    List<Object[]> countUnreadMessagesInChats(@Param("chatIds") Collection<Long> chatIds,
                                             @Param("userId") Long userId);

    /**
     * Who has read each of {@code messageIds}, as {@code [messageId, userId]} rows.
     *
     * <p>Replaces a per-message, per-recipient {@link #findByMessageIdAndUserId} call while a page of
     * messages is being mapped - for a group that was one query per member per message. Being a JPQL
     * query it triggers Hibernate's auto-flush first, so a receipt deleted earlier in the same
     * transaction (editMessage does exactly that) is correctly absent from the result.
     */
    @Query("SELECT r.message.id, r.user.id FROM ReadReceipt r WHERE r.message.id IN :messageIds")
    List<Object[]> findReaderIdsByMessageIds(@Param("messageIds") Collection<Long> messageIds);
}
