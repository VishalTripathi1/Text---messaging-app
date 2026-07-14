package com.messaging.domain.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * ChatResponse - DTO representing a one-to-one chat session
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChatResponse {
    private Long id;
    private UserResponse otherUser;
    private MessageResponse lastMessage;
    private Boolean isPinned;
    private Boolean isArchived;
    private Integer unreadCount;
}
