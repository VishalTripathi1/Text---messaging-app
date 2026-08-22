package com.messaging.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * ReadReceipt Entity - Tracks when messages are read
 */
@Entity
@Table(name = "read_receipts",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_receipt_message_user", columnNames = {"message_id", "user_id"})
    },
    indexes = {
        @Index(name = "idx_receipt_message", columnList = "message_id"),
        @Index(name = "idx_receipt_user", columnList = "user_id"),
        @Index(name = "idx_read_at", columnList = "read_at")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReadReceipt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @CreationTimestamp
    @Column(name = "read_at", nullable = false)
    private LocalDateTime readAt;
}
