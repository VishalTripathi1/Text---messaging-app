package com.messaging.infrastructure.repository;

import com.messaging.domain.entity.DeliveryStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * DeliveryStatusRepository - Data access for DeliveryStatus entity
 */
@Repository
public interface DeliveryStatusRepository extends JpaRepository<DeliveryStatus, Long> {
    Optional<DeliveryStatus> findByMessageIdAndRecipientId(Long messageId, Long recipientId);

    List<DeliveryStatus> findByRecipientIdAndStatus(Long recipientId, DeliveryStatus.DeliveryStatusType status);

    /**
     * Same query as findByRecipientIdAndStatus but eager-fetches message.sender and
     * message.chat in the same round trip. Previously processPendingDeliveries() looped over
     * the plain (lazy) version and touched status.getMessage().getSender() /
     * status.getMessage().getChat() per row - each access was a separate SELECT, so a user
     * with N pending messages triggered roughly 2N extra queries just to notify their senders
     * on reconnect (an N+1 query storm).
     */
    @org.springframework.data.jpa.repository.Query(
        "SELECT ds FROM DeliveryStatus ds " +
        "JOIN FETCH ds.message m " +
        "JOIN FETCH m.sender " +
        "LEFT JOIN FETCH m.chat " +
        "WHERE ds.recipient.id = :recipientId AND ds.status = :status"
    )
    List<DeliveryStatus> findByRecipientIdAndStatusWithMessageAndSender(
            @org.springframework.data.repository.query.Param("recipientId") Long recipientId,
            @org.springframework.data.repository.query.Param("status") DeliveryStatus.DeliveryStatusType status
    );
}
