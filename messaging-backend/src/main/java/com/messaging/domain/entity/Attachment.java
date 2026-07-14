package com.messaging.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/**
 * Attachment Entity - Stores file attachments
 */
@Entity
@Table(name = "attachments", indexes = {
    @Index(name = "idx_attachment_message", columnList = "message_id"),
    @Index(name = "idx_attachment_type", columnList = "file_type")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Attachment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    @Column(nullable = false, length = 255)
    private String fileName;

    @Column(name = "file_type", length = 50)
    private String fileType;

    @Column
    private Long fileSize;

    @Column(nullable = false, length = 500)
    private String filePath;

    @Column(length = 500)
    private String thumbnailPath;

    @Column
    private Integer durationSeconds;

    @Column(length = 100)
    private String mimeType;

    @Column(nullable = false)
    private Boolean isDownloaded = false;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
