package com.messaging.infrastructure.repository;

import com.messaging.domain.entity.MessageReaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * MessageReactionRepository - Data access for MessageReaction entity
 */
@Repository
public interface MessageReactionRepository extends JpaRepository<MessageReaction, Long> {
    List<MessageReaction> findByMessageId(Long messageId);
    Optional<MessageReaction> findByMessageIdAndUserId(Long messageId, Long userId);

    /**
     * Every reaction on any of {@code messageIds}, as {@code [messageId, userId, username, reaction]}
     * rows.
     *
     * <p>One query for a whole page instead of one per message, and the username is selected rather
     * than reached through {@code reaction.getUser()}, which would lazy-load a User per distinct
     * reactor. See MessageServiceImpl.mapToMessageResponses.
     */
    @Query("SELECT r.message.id, r.user.id, r.user.username, r.reaction FROM MessageReaction r " +
           "WHERE r.message.id IN :messageIds ORDER BY r.id ASC")
    List<Object[]> findReactionRowsByMessageIds(@Param("messageIds") Collection<Long> messageIds);
}
