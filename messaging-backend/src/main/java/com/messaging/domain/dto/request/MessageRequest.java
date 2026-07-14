package com.messaging.domain.dto.request;

import com.messaging.domain.entity.Message.MessageType;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MessageRequest - DTO for sending messages
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MessageRequest {
    private Long chatId;
    private Long groupId;
    private Long recipientId; // Used to resolve or create a chat if chatId is null
    
    @NotNull(message = "Message type cannot be null")
    private MessageType messageType;
    
    private String messageText;

    private String attachmentPath;
    private String attachmentFileName;
    private String attachmentFileType;
    private Long attachmentFileSize;
}
