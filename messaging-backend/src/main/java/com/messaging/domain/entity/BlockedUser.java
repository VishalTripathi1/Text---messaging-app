package com.messaging.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * BlockedUser Entity - Tracks blocked relationships
 */
@Entity
@Table(name = "blocked_users", indexes = {
    @Index(name = "idx_blocked_blocker", columnList = "blocker_id"),
    @Index(name = "idx_blocked_blocked", columnList = "blocked_id")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BlockedUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "blocker_id", nullable = false)
    private User blocker;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "blocked_id", nullable = false)
    private User blocked;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime blockedAt;
}
