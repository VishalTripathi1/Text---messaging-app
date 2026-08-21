package com.messaging.domain.dto.response;

import com.messaging.domain.entity.Message.MessageType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * MessageResponse - DTO for outgoing message details
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageResponse {
    private Long id;
    private Long chatId;
    private Long groupId;
    private Long senderId;
    private String senderUsername;
    private String messageText;
    private MessageType messageType;
    private Boolean isEdited;
    private LocalDateTime editedAt;
    private Boolean isDeleted;
    private LocalDateTime createdAt;

    private String attachmentPath;
    private String attachmentFileName;
    private String attachmentFileType;
    private Long attachmentFileSize;

    private java.util.List<ReactionResponse> reactions;
    private String deliveryStatus;
}
