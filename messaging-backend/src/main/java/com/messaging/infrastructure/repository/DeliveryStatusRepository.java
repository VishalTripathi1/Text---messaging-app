package com.messaging.infrastructure.repository;

import com.messaging.domain.entity.DeliveryStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * DeliveryStatusRepository - Data access for DeliveryStatus entity
 */
@Repository
public interface DeliveryStatusRepository extends JpaRepository<DeliveryStatus, Long> {
    Optional<DeliveryStatus> findByMessageIdAndRecipientId(Long messageId, Long recipientId);

    @Modifying
    @Query("DELETE FROM DeliveryStatus ds WHERE ds.message.id IN :messageIds")
    void deleteByMessageIds(@Param("messageIds") java.util.Collection<Long> messageIds);

    List<DeliveryStatus> findByRecipientIdAndStatus(Long recipientId, DeliveryStatus.DeliveryStatusType status);

    /**
     * A specific sender's still-PENDING messages to recipientId. Used when recipientId blocks
     * senderId: anything already sent but not yet delivered/read has to be frozen right then,
     * not just messages sent from that point on - otherwise a message that happened to still be
     * PENDING (recipient was offline) at block time would deliver/read normally the next time
     * the recipient reconnects, blocked or not. See MessageServiceImpl.blockUser().
     */
    @Query("SELECT ds FROM DeliveryStatus ds WHERE ds.recipient.id = :recipientId " +
           "AND ds.message.sender.id = :senderId AND ds.status = :status")
    List<DeliveryStatus> findByRecipientIdAndSenderIdAndStatus(
            @Param("recipientId") Long recipientId,
            @Param("senderId") Long senderId,
            @Param("status") DeliveryStatus.DeliveryStatusType status);

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

    /**
     * Who each of {@code messageIds} has reached, as {@code [messageId, recipientId]} rows, limited to
     * rows in {@code status}.
     *
     * <p>Mapping a page of messages needs the delivery state of every message at once; doing it with
     * {@link #findByMessageIdAndRecipientId} cost one query per message (per member, in a group). See
     * MessageServiceImpl.mapToMessageResponses.
     */
    @org.springframework.data.jpa.repository.Query(
        "SELECT ds.message.id, ds.recipient.id FROM DeliveryStatus ds " +
        "WHERE ds.message.id IN :messageIds AND ds.status = :status"
    )
    List<Object[]> findRecipientIdsByMessageIdsAndStatus(
            @org.springframework.data.repository.query.Param("messageIds") java.util.Collection<Long> messageIds,
            @org.springframework.data.repository.query.Param("status") DeliveryStatus.DeliveryStatusType status
    );
}
