package com.messaging.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

/**
 * Device Entity - Tracks user devices for multi-device support
 */
@Entity
@Table(name = "devices", indexes = {
    @Index(name = "idx_user_device_id", columnList = "user_id"),
    @Index(name = "idx_device_id", columnList = "device_id"),
    @Index(name = "idx_fcm_token", columnList = "fcm_token")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "device_id", nullable = false, unique = true, length = 255)
    private String deviceId;

    @Column(length = 100)
    private String deviceName;

    @Column(length = 50)
    private String deviceType;

    @Column(length = 50)
    private String osVersion;

    @Column(length = 50)
    private String appVersion;

    @Column(name = "fcm_token", length = 500)
    private String fcmToken;

    @Column(nullable = false)
    private Boolean isActive = true;

    @Column
    private LocalDateTime lastActive;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
