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
    private Boolean isCleared;
    private Boolean isDeleted;
    // Whether the viewer has blocked the other participant - distinct from being blocked BY
    // them. Only ever true/relevant for the viewer's own outgoing block action; matches the
    // "You blocked this user" placeholder ChatScreen already shows inside an open conversation.
    private Boolean isBlockedByMe;
    private Integer unreadCount;
}
