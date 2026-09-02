package com.messaging.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * DeliveryStatus Entity - Tracks message delivery status
 */
@Entity
@Table(name = "delivery_status", indexes = {
    @Index(name = "idx_delivery_message", columnList = "message_id"),
    @Index(name = "idx_delivery_recipient", columnList = "recipient_id"),
    @Index(name = "idx_delivery_status", columnList = "status")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeliveryStatus {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recipient_id", nullable = false)
    private User recipient;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private DeliveryStatusType status;

    @Column
    private LocalDateTime deliveredAt;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    public enum DeliveryStatusType {
        // BLOCKED: recorded when the recipient had the sender blocked at the moment this
        // message was sent. Deliberately distinct from PENDING - PENDING can still resolve to
        // DELIVERED once the recipient comes online, but BLOCKED must never progress even after
        // the recipient later unblocks the sender. See MessageServiceImpl.sendMessage().
        PENDING, DELIVERED, FAILED, BLOCKED
    }
}
