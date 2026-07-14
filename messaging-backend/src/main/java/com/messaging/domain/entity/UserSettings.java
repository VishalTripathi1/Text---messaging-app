package com.messaging.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * UserSettings Entity - Stores user application settings
 */
@Entity
@Table(name = "user_settings")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    private User user;

    @Column(length = 20)
    @Builder.Default
    private String theme = "LIGHT";

    @Column(name = "notifications_enabled")
    @Builder.Default
    private Boolean notificationsEnabled = true;

    @Column(name = "sound_enabled")
    @Builder.Default
    private Boolean soundEnabled = true;

    @Column(name = "vibration_enabled")
    @Builder.Default
    private Boolean vibrationEnabled = true;

    @Column(name = "show_read_receipts")
    @Builder.Default
    private Boolean showReadReceipts = true;

    @Column(name = "show_typing_indicator")
    @Builder.Default
    private Boolean showTypingIndicator = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
