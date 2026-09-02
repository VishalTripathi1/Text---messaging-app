package com.messaging.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * ChatUserPreference Entity - Stores per-user preferences for each chat
 * (pin and archive are per-user, not shared between chat participants)
 */
@Entity
@Table(name = "chat_user_preferences",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_chat_user", columnNames = {"chat_id", "user_id"})
    },
    indexes = {
        @Index(name = "idx_cup_user", columnList = "user_id"),
        @Index(name = "idx_cup_chat", columnList = "chat_id")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatUserPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "chat_id", nullable = false)
    private Long chatId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "is_pinned", nullable = false)
    @Builder.Default
    private Boolean isPinned = false;

    @Column(name = "is_archived", nullable = false)
    @Builder.Default
    private Boolean isArchived = false;

    @Column(name = "is_cleared", nullable = false)
    @Builder.Default
    private Boolean isCleared = false;

    @Column(name = "is_deleted", nullable = false)
    @Builder.Default
    private Boolean deleted = false;

    // Messages created before this timestamp are hidden from this user's view of the chat.
    // Without it, isCleared/deleted are just booleans with nothing to compare a message's
    // createdAt against, so re-opening a "cleared" chat showed the full history again.
    @Column(name = "cleared_at")
    private LocalDateTime clearedAt;

    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
